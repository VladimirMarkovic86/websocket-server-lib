(ns websocket-server-lib.core
  (:require [clojure.string :as cstring]
            [utils-lib.core :as utils]
            [ajax-lib.http.status-code :as stc
                                       :refer [status-code]]
            [ajax-lib.http.entity-header :as eh]
            [ajax-lib.http.mime-type :as mt]
            [clojure.java.io :as io])
  (:import [websocket_server_lib RejectedExecutionHandlerWebSocketResponse]
           [java.net ServerSocket]
           [javax.net.ssl SSLServerSocket
                          KeyManagerFactory
                          SSLContext]
           [java.security KeyStore]
           [java.util.concurrent Executors]))

(def main-thread
     (atom nil))

(def server-socket
     (atom nil))

(def running
     (atom false))

(def thread-pool-size 4)

(def thread-pool
     (atom nil))

(def client-sockets
     (atom #{}))

(def keep-alive-message-period 25)

(defn pow
  "Square of value"
  [value]
  (Math/pow
    2
    value))

(defn- open-server-socket
  "Open server socket for listening on particular port"
  [port
   & [{keystore-file-path :keystore-file-path
       keystore-type :keystore-type
       keystore-password :keystore-password
       ssl-context :ssl-context}]]
  (when (or (nil? @server-socket)
            (and (not (nil? @server-socket))
                 (.isClosed @server-socket))
         )
    (when (and keystore-file-path
               keystore-password)
      (try
        (let [ks (KeyStore/getInstance
                   (or keystore-type
                       "JKS"))
              ks-is (io/input-stream
                      (io/resource
                        keystore-file-path))
              pass-char-array (char-array
                                keystore-password)
              void (.load
                     ks
                     ks-is
                     pass-char-array)
              kmf (KeyManagerFactory/getInstance
                    (KeyManagerFactory/getDefaultAlgorithm))
              sc (SSLContext/getInstance
                   (or ssl-context
                       "TLSv1.2"))
              void (.init
                     kmf
                     ks
                     pass-char-array)
              void (.init
                     sc
                     (.getKeyManagers
                       kmf)
                     nil
                     nil)
              ssl-server-socket (.createServerSocket
                                  (.getServerSocketFactory
                                    sc)
                                  port)]
          (.setEnabledProtocols
            ssl-server-socket
            (into-array
              ["TLSv1"
               "TLSv1.1"
               "TLSv1.2"
               "SSLv3"]))
          (reset!
            server-socket
            ssl-server-socket))
        (catch Exception e
          (println (.getMessage
                     e))
          ))
     )
    (when-not (and keystore-file-path
                   keystore-password)
      (reset!
        server-socket
        (ServerSocket.
          port))
     ))
  @server-socket)

(defn stop-server
  "Stop server"
  []
  (when (and @server-socket
             @main-thread
             @running)
    (try
      (reset!
        running
        false)
      (future-cancel
        @main-thread)
      (doseq [client-socket @client-sockets]
        (try
          (.close
            client-socket)
          (catch Exception e
            (println (.getMessage e))
           ))
       )
      (swap!
        client-sockets
        empty)
      (.shutdownNow
        @thread-pool)
      (.close
        @server-socket)
      (catch Exception e
        (println (.getMessage e))
       ))
   )
   (println "WebSocket server stopped"))

(defn- read-header
  "Read XMLHTTPRequest headers into clojure map"
  [header]
  (let [header-vector (cstring/split
                        header
                        #"\r\n")
        request-start-line (get header-vector 0)
        request-start-line-vector (cstring/split
                                    request-start-line
                                    #" ")
        request-method (get request-start-line-vector 0)
        request-uri (get request-start-line-vector 1)
        request-protocol (get request-start-line-vector 2)
        header-vector (utils/remove-index-from-vector
                        header-vector
                        0)
        header-map (atom
                     {:request-method request-method
                      :request-uri request-uri
                      :request-protocol request-protocol})]
    (doseq [header-line header-vector]
      (let [header-line-vector (cstring/split
                                 header-line
                                 #": "
                                 2)
            key-name (cstring/lower-case
                       (get header-line-vector 0))
            key-value (get header-line-vector 1)]
        (swap!
          header-map
          assoc
          (keyword
            key-name)
          key-value))
     )
   @header-map))

(defn- pack-response
  "Pack response from clojure map into string"
  [request
   response-map]
  (let [response (atom "")
        status-line (str
                      (or (:request-protocol request)
                          "HTTP/1.1")
                      " "
                      (status-code
                        (:status response-map))
                      "\r\n")
        headers (:headers response-map)]
    (swap!
      response
      str
      status-line)
    (doseq [map-key (keys headers)]
      (swap!
        response
        str
        map-key
        ": "
        (get headers map-key)
        "\r\n"))
    @response))

(defn- handler-fn
  "Handle WebSocket handshake"
  [request]
  (pack-response
    request
    (if (and (= (:upgrade request)
                "websocket"))
      (let [sec-websocket-key (:sec-websocket-key request)
            sec-websocket-accept (javax.xml.bind.DatatypeConverter/printBase64Binary
                                   (.digest
                                     (java.security.MessageDigest/getInstance
                                       "SHA-1")
                                     (.getBytes
                                       (str
                                         sec-websocket-key
                                         "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                       "UTF-8"))
                                  )]
        {:status 101
         :headers {"Connection" "Upgrade"
                   "Upgrade" "websocket"
                   "Sec-WebSocket-Accept" sec-websocket-accept}})
      {:status 404
       :headers {"Content-Type" "text-plain"}
       :body (str {:message "Operation not supported"})})
   ))

(defn decode-message
  "Decode message sent through websocket
   
   https://tools.ietf.org/html/rfc6455#section-5.2"
  [encoded-bytes
   key-array]
  (let [message (atom [])]
    (doseq [itr (range
                  (count encoded-bytes))]
      (swap!
        message
        conj
        (bit-xor
          (get
            encoded-bytes
            itr)
          (get
            key-array
            (bit-and
              itr
              0x3))
         ))
     )
    @message))

(defn encode-message
  "Encoding message to send it through websocket
  
   https://tools.ietf.org/html/rfc6455#section-5.2"
  [message
   & [first-byte
      user-agent]]
  (let [first-byte (or first-byte
                       -127)
        key-el-one (unchecked-byte
                     (int
                       (* (Math/random)
                          255))
                    )
        key-el-two (unchecked-byte
                     (int
                       (* (Math/random)
                          255))
                    )
        key-el-three (unchecked-byte
                       (int
                         (* (Math/random)
                            255))
                      )
        key-el-four (unchecked-byte
                      (int
                        (* (Math/random)
                           255))
                     )
        key-array (if user-agent
                    []
                    [key-el-one
                     key-el-two
                     key-el-three
                     key-el-four])
        message-bytes (if (= first-byte
                             -120)
                        (.getBytes
                          (str
                            "  "
                            message)
                          "UTF-8")
                        (.getBytes
                          message
                          "UTF-8"))
        encoded-message (if user-agent
                          (atom message-bytes)
                          (atom []))]
    (when-not user-agent
      (doseq [itr (range
                    (count
                      message-bytes))]
        (swap!
          encoded-message
          conj
          (bit-xor
            (get
              message-bytes
              itr)
            (get
              key-array
              (bit-and
                itr
                0x3))
           ))
       ))
    (let [message-count (count @encoded-message)
          ;debug (println message-count)
          length-bytes (cond
                         (< (dec (pow 7))
                            message-count
                            (pow 16))
                           (let [third-byte-count (/ message-count
                                                     (pow 8))
                                 third-byte (if (< third-byte-count
                                                   1)
                                               0
                                               (unchecked-byte
                                                 third-byte-count))
                                 message-count (- message-count
                                                  (* third-byte
                                                     (pow 8))
                                                )
                                 fourth-byte-count (/ message-count
                                                      (pow 0))
                                 fourth-byte (if (< fourth-byte-count
                                                    1)
                                              0
                                              (unchecked-byte
                                                fourth-byte-count))
                                 message-count (- message-count
                                                  (* fourth-byte
                                                     (pow 0))
                                                )]
                             ;(println third-byte)
                             ;(println fourth-byte)
                             [(if user-agent
                                126
                                (- 126
                                   128))
                              third-byte
                              fourth-byte])
                         (< (dec (pow 16))
                            message-count)
                           (let [third-byte-count (/ message-count
                                                     (pow 56))
                                 third-byte (if (< third-byte-count
                                                   1)
                                              0
                                              (unchecked-byte
                                                third-byte-count))
                                 message-count (- message-count
                                                  (* third-byte
                                                     (pow 56))
                                                )
                                 fourth-byte-count (/ message-count
                                                      (pow 48))
                                 fourth-byte (if (< fourth-byte-count
                                                    1)
                                               0
                                               (unchecked-byte
                                                 fourth-byte-count))
                                 message-count (- message-count
                                                  (* fourth-byte
                                                     (pow 48))
                                                )
                                 fifth-byte-count (/ message-count
                                                     (pow 40))
                                 fifth-byte (if (< fifth-byte-count
                                                   1)
                                              0
                                              (unchecked-byte
                                                fifth-byte-count))
                                 message-count (- message-count
                                                  (* fifth-byte
                                                     (pow 40))
                                                )
                                 sixth-byte-count (/ message-count
                                                     (pow 32))
                                 sixth-byte (if (< sixth-byte-count
                                                   1)
                                              0
                                              (unchecked-byte
                                                sixth-byte-count))
                                 message-count (- message-count
                                                  (* sixth-byte
                                                     (pow 32))
                                                )
                                 seventh-byte-count (/ message-count
                                                       (pow 24))
                                 seventh-byte (if (< seventh-byte-count
                                                     1)
                                                0
                                                (unchecked-byte
                                                  seventh-byte-count))
                                 message-count (- message-count
                                                  (* seventh-byte
                                                     (pow 24))
                                                )
                                 eighth-byte-count (/ message-count
                                                      (pow 16))
                                 eighth-byte (if (< eighth-byte-count
                                                    1)
                                              0
                                              (unchecked-byte
                                                eighth-byte-count))
                                 message-count (- message-count
                                                  (* eighth-byte
                                                     (pow 16))
                                                )
                                 nineth-byte-count (/ message-count
                                                      (pow 8))
                                 nineth-byte (if (< nineth-byte-count
                                                    1)
                                               0
                                               (unchecked-byte
                                                 nineth-byte-count))
                                 message-count (- message-count
                                                  (* nineth-byte
                                                     (pow 8))
                                                )
                                 tenth-byte-count (/ message-count
                                                     (pow 0))
                                 tenth-byte (if (< tenth-byte-count
                                                   1)
                                              0
                                              (unchecked-byte
                                                tenth-byte-count))
                                 message-count (- message-count
                                                  (* tenth-byte
                                                     (pow 0))
                                                )]
                             ;(println message-count)
                             ;(println third-byte)
                             ;(println fourth-byte)
                             ;(println fifth-byte)
                             ;(println sixth-byte)
                             ;(println seventh-byte)
                             ;(println eighth-byte)
                             ;(println nineth-byte)
                             ;(println tenth-byte)
                             [(if user-agent
                                127
                                (- 127
                                   128))
                              third-byte
                              fourth-byte
                              fifth-byte
                              sixth-byte
                              seventh-byte
                              eighth-byte
                              nineth-byte
                              tenth-byte])
                         :else
                         [(if user-agent
                            message-count
                            (- message-count
                               128))]
                        )
          message-array (byte-array
                          (apply
                            conj
                            (apply
                              conj
                              (apply
                                conj
                                [first-byte]
                                length-bytes)
                              key-array)
                            @encoded-message))]
      message-array))
 )

(defn- calculate-message-length
  "Calculate message length from first bytes
  
   https://tools.ietf.org/html/rfc6455#section-5.2"
  [second-byte
   input-stream
   user-agent]
  (case (int
          (+ second-byte
             (pow 7))
         )
    126 (let [third-byte (* (.read
                              input-stream)
                            (pow 8))
              fourth-byte (.read
                            input-stream)]
          ;(println third-byte)
          ;(println fourth-byte)
          (+ third-byte
             fourth-byte))
    127 (let [third-byte (* (.read
                              input-stream)
                            (pow 56))
              fourth-byte (* (.read
                               input-stream)
                             (pow 48))
              fifth-byte (* (.read
                              input-stream)
                            (pow 40))
              sixth-byte (* (.read
                              input-stream)
                            (pow 32))
              seventh-byte (* (.read
                                input-stream)
                              (pow 24))
              eighth-byte (* (.read
                               input-stream)
                             (pow 16))
              nineth-byte (* (.read
                               input-stream)
                             (pow 8))
              tenth-byte (.read
                           input-stream)]
          ;(println third-byte)
          ;(println fourth-byte)
          ;(println fifth-byte)
          ;(println sixth-byte)
          ;(println seventh-byte)
          ;(println eighth-byte)
          ;(println nineth-byte)
          ;(println tenth-byte)
          (+ third-byte
             fourth-byte
             fifth-byte
             sixth-byte
             seventh-byte
             eighth-byte
             nineth-byte
             tenth-byte))
    (int
      (+ second-byte
         (pow 7))
     ))
 )

(defn read-till-fin-is-one
  "Read from input stream all messages till FIN bit has value 1 (one)"
  [input-stream
   user-agent]
  (let [message (atom [])
        fin-atom (atom 0)]
    (while (not= @fin-atom
                 1)
      (let [w-first-byte (.read
                           input-stream)
            first-byte-binary (Long/toBinaryString
                                w-first-byte)
            first-byte-binary (let [first-byte-count (- 8
                                                        (count first-byte-binary))
                                    first-byte-atom (atom first-byte-binary)]
                                (doseq [itr (range first-byte-count)]
                                  (swap!
                                    first-byte-atom
                                    str
                                    0))
                                @first-byte-atom)
            fin (.charAt
                  first-byte-binary
                  7)
            rsv1 (.charAt
                   first-byte-binary
                   6)
            rsv2 (.charAt
                   first-byte-binary
                   5)
            rsv3 (.charAt
                   first-byte-binary
                   4)
            opcode3 (if (= (.charAt
                             first-byte-binary
                             3)
                           \1)
                      (pow 3)
                      0)
            opcode2 (if (= (.charAt
                             first-byte-binary
                             2)
                           \1)
                      (pow 2)
                      0)
            opcode1 (if (= (.charAt
                             first-byte-binary
                             1)
                           \1)
                      (pow 1)
                      0)
            opcode0 (if (= (.charAt
                             first-byte-binary
                             0)
                           \1)
                      (pow 0)
                      0)
            opcode (int
                     (+ opcode3
                        opcode2
                        opcode1
                        opcode0))
            second-byte (unchecked-byte
                          (.read
                            input-stream))
            message-length (calculate-message-length
                             second-byte
                             input-stream
                             user-agent)
            key-vector [(unchecked-byte
                          (.read
                            input-stream))
                        (unchecked-byte
                          (.read
                            input-stream))
                        (unchecked-byte
                          (.read
                            input-stream))
                        (unchecked-byte
                          (.read
                            input-stream))]
            encoded-vector (atom [])]
        ;(println (str key-vector))
        ;(println "FIN" fin)
        ;(println "RSV1" rsv1)
        ;(println "RSV2" rsv2)
        ;(println "RSV3" rsv3)
        ;(println "OPCODE" opcode)
        ;(println "Payload len" message-length)
        (if user-agent
          (swap!
            fin-atom
            +
            (if (or (= opcode
                       0)
                    (= opcode
                       1))
              (* opcode
                 1/2)
              1))
          (reset!
            fin-atom
            (read-string
              (str fin))
           ))
        (doseq [itr (range message-length)]
          (let [read-byte (.read
                            input-stream)]
            (swap!
              encoded-vector
              conj
              (unchecked-byte
                read-byte))
           ))
        (swap!
          message
          (fn [atom-value
               param-value]
            (apply
              conj
              atom-value
              param-value))
          (decode-message
            @encoded-vector
            key-vector))
       ))
   (String.
     (byte-array
       @message)
     "UTF-8"))
 )

(defn accept-web-socket-request-subprocess
  "Work with established websocket connection"
  [routing-fn
   client-socket
   header-map-with-body]
  (try
    (let [sub-running (atom true)
          input-stream (.getInputStream
                         client-socket)
          output-stream (.getOutputStream
                          client-socket)
          user-agent (:user-agent header-map-with-body)
          user-agent (clojure.string/index-of
                       user-agent
                       "Chrome")
          keep-alive (atom true)
          keep-alive-time-in-seconds (atom keep-alive-message-period)
          keep-alive-thread
           (future
             (while @keep-alive
               (while (< 0
                         @keep-alive-time-in-seconds)
                 (Thread/sleep 1000)
                 (swap!
                   keep-alive-time-in-seconds
                   dec))
               (.write
                 output-stream
                 (encode-message
                   (str
                     {:action "keep-alive"})
                   nil
                   user-agent))
               (.flush
                 output-stream)
               (reset!
                 keep-alive-time-in-seconds
                 keep-alive-message-period))
            )]
      (while @sub-running
        (let [decoded-message (read-till-fin-is-one
                                input-stream
                                user-agent)
              {request-method :request-method} header-map-with-body
              header-map-with-body (assoc
                                     header-map-with-body
                                     :request-method
                                     (str
                                       "ws "
                                       request-method))]
          (routing-fn
            (assoc
              header-map-with-body
              :websocket
               {:websocket-message decoded-message
                :websocket-message-length (count decoded-message)
                :websocket-output-fn (fn [server-message
                                          & [first-byte]]
                                       (try
                                         (.write
                                           output-stream
                                           (encode-message
                                             server-message
                                             first-byte
                                             user-agent))
                                         (.flush
                                           output-stream)
                                         (reset!
                                           keep-alive-time-in-seconds
                                           keep-alive-message-period)
                                         (when (= first-byte
                                                  -120)
                                           (reset!
                                             sub-running
                                             false)
                                           (.close
                                             client-socket)
                                           (swap!
                                             client-sockets
                                             disj
                                             client-socket)
                                           (reset!
                                             keep-alive
                                             false)
                                           (reset!
                                             keep-alive-time-in-seconds
                                             0)
                                           (future-cancel
                                             keep-alive-thread))
                                         (catch Exception e
                                           (println (.getMessage e))
                                          ))
                                       nil)})
           ))
       ))
    (catch Exception e
      (println (.getMessage e))
     ))
 )

(defn- accept-web-socket-request
  "Read websocket request bytes
   process parsed request,
   pass it through routing-fn defined on server side of application
   establish connection and send response"
  [routing-fn
   client-socket
   reject]
  (try
    (let [input-stream (.getInputStream
                         client-socket)
          first-byte (.read
                       input-stream)
          available-bytes (.available
                            input-stream)
          available-bytes (inc
                            available-bytes)
          output-stream (.getOutputStream
                          client-socket)
          request-vector (atom
                           [first-byte])
          read-stream (while (< (count
                                  @request-vector)
                                available-bytes)
                        (let [read-byte (unchecked-byte
                                          (.read
                                            input-stream))]
                          (swap!
                            request-vector
                            conj
                            read-byte))
                       )
          read-byte-array (byte-array
                            available-bytes
                            @request-vector)
          request (String.
                    read-byte-array
                    "UTF-8")
          ;debug (println request)
          [header
           body] (cstring/split
                   request
                   #"\r\n\r\n")
          header-map (read-header
                       header)
          header-map-with-body (assoc
                                 header-map
                                 :body
                                 body)
          response (handler-fn
                     header-map-with-body)
          user-agent (:user-agent header-map-with-body)
          connect-message (encode-message
                            "Здраво"
                            nil
                            user-agent)
          response (str
                     response
                     (eh/content-length)
                     ": "
                     (count connect-message)
                     "\r\n\r\n")
          response-as-byte-array (.getBytes
                                   response
                                   "UTF-8")]
      ;(println response)
      (.write
        output-stream
        response-as-byte-array)
      (.write
        output-stream
        connect-message)
      (if reject
        (.write
          output-stream
          (encode-message
            (str
              {:action "rejected"
               :status "error"
               :message "Try again later"})
            -120
            user-agent))
        (accept-web-socket-request-subprocess
          routing-fn
          client-socket
          header-map-with-body))
     )
    ;(println "Connection established")
    (catch Exception e
      (println (.getMessage e))
     )
    (finally
      (.close
        client-socket)
      (swap!
        client-sockets
        disj
        client-socket))
   ))

(defn- while-loop
  "While loop of accepting and responding on clients websocket requests"
  [routing-fn]
  (try
    (while @running
      (let [client-socket (.accept
                            @server-socket)]
        (swap!
          client-sockets
          conj
          client-socket)
        (.execute
          @thread-pool
          (fn [& [reject]]
            (accept-web-socket-request
              routing-fn
              client-socket
              reject))
         ))
     )
    (catch Exception e
      (println (.getMessage e))
     ))
 )

(defn start-server
  "Start websocket server"
  [routing-fn
   & [port
      https-conf]]
  (try
    (open-server-socket
      (or port
          9000)
      https-conf)
    (if (or (nil? @main-thread)
            (and (not (nil? @main-thread))
                 (future-cancelled?
                   @main-thread)
                 (future-done?
                   @main-thread))
         )
      (do
        (reset!
          running
          true)
        (reset!
          thread-pool
          (java.util.concurrent.ThreadPoolExecutor.
            thread-pool-size
            thread-pool-size
            0
            java.util.concurrent.TimeUnit/MILLISECONDS
            (java.util.concurrent.SynchronousQueue.))
         )
        (.setRejectedExecutionHandler
          @thread-pool
          (RejectedExecutionHandlerWebSocketResponse.))
        (reset!
          main-thread
          (future
            (while-loop
              routing-fn))
         )
        (println "WebSocket server started")
        @main-thread)
      (println "WebSocket server already started"))
    (catch Exception e
      (println (.getMessage e))
     ))
 )

