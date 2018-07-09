(ns websocket-server-lib.core
  (:require [clojure.string :as cstring]
            [utils-lib.core :as utils]
            [ajax-lib.http.status-code :refer [status-code]]
            [ajax-lib.http.entity-header :as eh])
  (:import [java.net ServerSocket]))

(def main-thread
     (atom nil))

(def server-socket
     (atom nil))

(defn pow
  "Square of value"
  [value]
  (Math/pow
    2
    value))

(defn- open-server-socket
  "Open server socket for listening on particular port"
  [port]
  (when (or (nil? @server-socket)
            (and (not (nil? @server-socket))
                 (.isClosed @server-socket))
         )
    (reset!
      server-socket
      (ServerSocket.
        port))
   )
  @server-socket)

(defn stop-server
  "Stop server"
  []
  (when (and @server-socket
             @main-thread)
    (try
      (.interrupt
        @main-thread)
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
                      (:request-protocol request)
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
    (if (= (:upgrade request)
         "websocket")
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
      {:status 404}))
 )

(defn decode-message
  "Decode message sent through websocket
   
   https://tools.ietf.org/html/rfc6455#section-5.2"
  [encoded-bytes
   key-array]
  (let [message (atom [])]
    (doseq [itr (range (count encoded-bytes))]
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
     (String.
       (byte-array
         @message)
       "UTF-8"))
 )

(defn encode-message
  "Encoding message to send it through websocket
  
   https://tools.ietf.org/html/rfc6455#section-5.2"
  [message
   & [first-byte]]
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
        key-array [key-el-one
                   key-el-two
                   key-el-three
                   key-el-four]
        message-bytes (.getBytes
                        message
                        "UTF-8")
        encoded-message (atom [])]
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
     )
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
                             [(- 126
                                 128)
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
                             [(- 127
                                 128)
                              third-byte
                              fourth-byte
                              fifth-byte
                              sixth-byte
                              seventh-byte
                              eighth-byte
                              nineth-byte
                              tenth-byte])
                         :else
                         [(- message-count
                             128)])
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
   input-stream]
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

(defn- accept-web-socket-request
  "Read websocket request bytes
   process parsed request,
   pass it through routing-fn defined on server side of application
   establish connection and send response"
  [routing-fn
   client-socket]
  (try
    (let [input-stream (.getInputStream
                         client-socket)
          available-bytes (.available
                            input-stream)
          output-stream (.getOutputStream
                          client-socket)
          read-byte-array (byte-array
                            available-bytes)
          read-int (.read
                     input-stream
                     read-byte-array
                     0
                     available-bytes)
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
          connect-message (encode-message
                            "Здраво")
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
       (while true
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
                                input-stream)
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
           (doseq [itr (range message-length)]
             (let [read-byte (.read
                               input-stream)]
               (swap!
                 encoded-vector
                 conj
                 (unchecked-byte
                   read-byte))
              ))
           (let [{request-method :request-method
                  request-uri :request-uri} header-map-with-body
                 request-start-line (str
                                      "ws "
                                      request-method
                                      " "
                                      request-uri)]
             (routing-fn
               request-start-line
               (assoc
                 header-map-with-body
                 :websocket
                  {:websocket-message (decode-message
                                        @encoded-vector
                                        key-vector)
                   :websocket-output-fn (fn [server-message
                                             & [first-byte]]
                                          (.write
                                            output-stream
                                            (encode-message
                                              server-message
                                              first-byte))
                                          (when (= first-byte
                                                   -120)
                                            (throw
                                              (Exception.
                                                "Initiated websocket closing."))
                                           ))}
                ))
            ))
        ))
    (catch Exception e
      ;(println (.printStackTrace e))
      (println (.getMessage e))
      (println "Socket closed"))
   )
  (println "Out of while"))

(defn start-server
  "Start websocket server"
  [routing-fn
   & [port]]
  (open-server-socket
    (or port
        9000))
  (if (or (nil? @main-thread)
          (and (not (nil? @main-thread))
               (not (.isAlive @main-thread))
           ))
    (let [while-task (fn []
                       (try
                         (while true
                           (let [client-socket (.accept @server-socket)
                                 task (fn []
                                        (accept-web-socket-request
                                          routing-fn
                                          client-socket))]
                             (.start (Thread. task))
                            ))
                         (catch Exception e
                           (println (.getMessage e))
                          ))
                      )]
      (reset!
        main-thread
        (Thread. while-task))
      (.start
        @main-thread)
      (println "WebSocket server started"))
    (println "WebSocket server already started"))
  @main-thread)

