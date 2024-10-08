package com.github.lzyzsd.jsbridge;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@SuppressLint("SetJavaScriptEnabled")
public class BridgeWebView extends WebView implements WebViewJavascriptBridge {

	public static final String toLoadJs = "WebViewJavascriptBridge.js";
	Map<String, CallBackFunction> responseCallbacks = new HashMap<>();
	Map<String, BridgeHandler> messageHandlers = new HashMap<>();
	BridgeHandler defaultHandler = new DefaultHandler();

	private List<Message> startupMessage = new ArrayList<>();

	public List<Message> getStartupMessage() {
		return startupMessage;
	}

	private List<String> whiteList = new ArrayList<>();
	private boolean whiteListEnable = false;

	public void setStartupMessage(List<Message> startupMessage) {
		this.startupMessage = startupMessage;
	}

	public void setWhiteList(List<String> whiteList) {
		this.whiteList = whiteList;
	}

	public void setWhiteListEnable(boolean whiteListEnable) {
		this.whiteListEnable = whiteListEnable;
	}

	private long uniqueId = 0;

	public BridgeWebView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	public BridgeWebView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		init();
	}

	public BridgeWebView(Context context) {
		super(context);
		init();
	}

	/**
	 * 
	 * @param handler
	 *            default handler,handle messages send by js without assigned handler name,
     *            if js message has handler name, it will be handled by named handlers registered by native
	 */
	public void setDefaultHandler(BridgeHandler handler) {
       this.defaultHandler = handler;
	}

    private void init() {
		this.setVerticalScrollBarEnabled(false);
		this.setHorizontalScrollBarEnabled(false);
		this.getSettings().setJavaScriptEnabled(true);
		WebView.setWebContentsDebuggingEnabled(true);
		this.setWebViewClient(generateBridgeWebViewClient());
	}

    protected BridgeWebViewClient generateBridgeWebViewClient() {
        return new BridgeWebViewClient(this);
    }

	void handlerReturnData(String url) {
		String functionName = BridgeUtil.getFunctionFromReturnUrl(url);
		CallBackFunction f = responseCallbacks.get(functionName);
		String data = BridgeUtil.getDataFromReturnUrl(url);
		if (f != null) {
			f.onCallBack(data);
			responseCallbacks.remove(functionName);
		}
	}

	@Override
	public void send(String data) {
		send(data, null);
	}

	@Override
	public void send(String data, CallBackFunction responseCallback) {
		doSend(null, data, responseCallback);
	}

	private void doSend(String handlerName, String data, CallBackFunction responseCallback) {
		Message m = new Message();
		if (!TextUtils.isEmpty(data)) {
			m.setData(data);
		}
		if (responseCallback != null) {
			String callbackStr = String.format(BridgeUtil.CALLBACK_ID_FORMAT, ++uniqueId + (BridgeUtil.UNDERLINE_STR + SystemClock.currentThreadTimeMillis()));
			responseCallbacks.put(callbackStr, responseCallback);
			m.setCallbackId(callbackStr);
		}
		if (!TextUtils.isEmpty(handlerName)) {
			m.setHandlerName(handlerName);
		}
		queueMessage(m);
	}

	private void queueMessage(Message m) {
		if (startupMessage != null) {
			startupMessage.add(m);
		} else {
			dispatchMessage(m);
		}
	}

	void dispatchMessage(Message m) {
        String messageJson = m.toJson();
        //escape special characters for json string
        messageJson = messageJson.replaceAll("(\\\\)([^utrn])", "\\\\\\\\$1$2");
        messageJson = messageJson.replaceAll("(?<=[^\\\\])(\")", "\\\\\"");
		messageJson = messageJson.replaceAll("(?<=[^\\\\])(\')", "\\\\\'");
        String javascriptCommand = String.format(BridgeUtil.JS_HANDLE_MESSAGE_FROM_JAVA, messageJson);
        if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
            this.loadUrl(javascriptCommand);
        }
    }

	void flushMessageQueue() {
		if (Thread.currentThread() != Looper.getMainLooper().getThread()) {
			return;
		}
		loadUrl(BridgeUtil.JS_FETCH_QUEUE_FROM_JAVA, data -> {
			// deserializeMessage
			List<Message> list;
			try {
				list = Message.toArrayList(data);
			} catch (Exception e) {
				e.printStackTrace();
				return;
			}
			if (list.isEmpty()) {
				return;
			}
			for (int i = 0; i < list.size(); i++) {
				Message m = list.get(i);
				String responseId = m.getResponseId();
				// 是否是response
				if (!TextUtils.isEmpty(responseId)) {
					CallBackFunction function = Objects.requireNonNull(responseCallbacks.get(responseId));
					String responseData = m.getResponseData();
					function.onCallBack(responseData);
					responseCallbacks.remove(responseId);
				} else {
					BridgeHandler handler;
					boolean isUseMessageHandlers = false;

					if (!TextUtils.isEmpty(m.getHandlerName())) {
						// 验证白名单
						if (whiteListEnable) {
							String url = getUrl();
							if (!TextUtils.isEmpty(url)) {
								for (String whiteHost : whiteList) {
									String host = Uri.parse(url).getHost();
									if (url.startsWith("file:///android_asset/") // 本地文件
											|| (!TextUtils.isEmpty(host) && host.endsWith(whiteHost))) {
										isUseMessageHandlers = true;
										break;
									}
								}
							}
						} else {
							isUseMessageHandlers = true;
						}
					}

					if (isUseMessageHandlers) {
						handler = messageHandlers.get(m.getHandlerName());
					} else {
						handler = defaultHandler;
					}
					if (handler == null) {
						return;
					}

					CallBackFunction responseFunction;
					// if had callbackId
					final String callbackId = m.getCallbackId();
					if (!TextUtils.isEmpty(callbackId)) {
						responseFunction = data1 -> {
							Message responseMsg = new Message();
							responseMsg.setResponseId(callbackId);
							responseMsg.setResponseData(data1);
							queueMessage(responseMsg);
						};
					} else {
						responseFunction = data12 -> {
							// do nothing
						};
					}

					handler.handler(m.getData(), responseFunction);
				}
			}
		});
	}

	public void loadUrl(String jsUrl, CallBackFunction returnCallback) {
		this.loadUrl(jsUrl);
		responseCallbacks.put(BridgeUtil.parseFunctionName(jsUrl), returnCallback);
	}

	/**
	 * register handler,so that javascript can call it
	 * 
	 * @param handlerName
	 * @param handler
	 */
	public void registerHandler(String handlerName, BridgeHandler handler) {
		if (handler != null) {
			messageHandlers.put(handlerName, handler);
		}
	}

	/**
	 * call javascript registered handler
	 *
     * @param handlerName
	 * @param data
	 * @param callBack
	 */
	public void callHandler(String handlerName, String data, CallBackFunction callBack) {
        doSend(handlerName, data, callBack);
	}
}
