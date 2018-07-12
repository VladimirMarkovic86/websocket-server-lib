package websocketserverlib;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

public class RejectedExecutionHandlerWebSocketResponse implements RejectedExecutionHandler {

	 @Override
	 public void rejectedExecution(Runnable worker, ThreadPoolExecutor executor) {
	   ((clojure.lang.IFn) worker).invoke(true);
	 }

}

