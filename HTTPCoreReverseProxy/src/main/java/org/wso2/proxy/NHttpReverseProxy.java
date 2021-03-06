package org.wso2.proxy;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLContext;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.EnglishReasonPhraseCatalog;
import org.apache.http.impl.nio.DefaultHttpClientIODispatch;
import org.apache.http.impl.nio.DefaultHttpServerIODispatch;
import org.apache.http.impl.nio.pool.BasicNIOConnFactory;
import org.apache.http.impl.nio.pool.BasicNIOConnPool;
import org.apache.http.impl.nio.pool.BasicNIOPoolEntry;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.DefaultListeningIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.NHttpConnection;
import org.apache.http.nio.NHttpServerConnection;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.nio.pool.NIOConnFactory;
import org.apache.http.nio.protocol.BasicAsyncResponseProducer;
import org.apache.http.nio.protocol.HttpAsyncExchange;
import org.apache.http.nio.protocol.HttpAsyncRequestConsumer;
import org.apache.http.nio.protocol.HttpAsyncRequestExecutor;
import org.apache.http.nio.protocol.HttpAsyncRequestHandler;
import org.apache.http.nio.protocol.HttpAsyncRequestHandlerMapper;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncRequester;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.nio.protocol.HttpAsyncResponseProducer;
import org.apache.http.nio.protocol.HttpAsyncService;
import org.apache.http.nio.protocol.UriHttpAsyncRequestHandlerMapper;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.ListeningIOReactor;
import org.apache.http.pool.PoolStats;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestExpectContinue;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;

public class NHttpReverseProxy {
	private static int LOCAL_PORT;
	private static String REMOTE_HOST = null;
	private static int REMOTE_PORT;

	private static boolean SECURE_BACKEND;
	private static boolean SECURE_PROXY;

	static String KEY_STORE_LOCATION;
	static String KEY_STORE_PASSWORD;

	private static String TRUST_STORE_LOCATION;
	private static String TRUST_STORE_PASSWORD;

	private static Properties prop = new Properties();
	private static SSLContext serverSSLContext;
	private static SSLContext clientSSLContext;

	public static void main(String[] args) throws URISyntaxException, UnrecoverableKeyException,
	                                      KeyManagementException, KeyStoreException,
	                                      NoSuchAlgorithmException, CertificateException,
	                                      FileNotFoundException, IOException {
		// initialize the properties first.
		init();

		String targetScheme = "http";
		if (SECURE_BACKEND) {
			targetScheme = "https";
		}

		// Target host
		HttpHost targetHost = new HttpHost(REMOTE_HOST, REMOTE_PORT, targetScheme);

		System.out.println("Reverse proxy to " + targetHost);

		IOReactorConfig config =
		                         IOReactorConfig.custom().setIoThreadCount(1).setSoTimeout(3000)
		                                        .setConnectTimeout(3000).build();
		final ConnectingIOReactor connectingIOReactor = new DefaultConnectingIOReactor(config);
		final ListeningIOReactor listeningIOReactor = new DefaultListeningIOReactor(config);

		// Set up HTTP protocol processor for incoming connections
		HttpProcessor inhttpproc =
		                           new ImmutableHttpProcessor(
		                                                      new HttpResponseInterceptor[] {
		                                                                                     new ResponseDate(),
		                                                                                     new ResponseServer(
		                                                                                                        "Test/1.1"),
		                                                                                     new ResponseContent(),
		                                                                                     new ResponseConnControl() });

		// Set up HTTP protocol processor for outgoing connections
		HttpProcessor outhttpproc =
		                            new ImmutableHttpProcessor(
		                                                       new HttpRequestInterceptor[] {
		                                                                                     new RequestContent(),
		                                                                                     new RequestTargetHost(),
		                                                                                     new RequestConnControl(),
		                                                                                     new RequestUserAgent(
		                                                                                                          "Test/1.1"),
		                                                                                     new RequestExpectContinue(
		                                                                                                               true) });

		ProxyClientProtocolHandler clientHandler = new ProxyClientProtocolHandler();
		HttpAsyncRequester executor =
		                              new HttpAsyncRequester(
		                                                     outhttpproc,
		                                                     new ProxyOutgoingConnectionReuseStrategy());

		ProxyConnPool connPool = createConnectionPool(connectingIOReactor);
		connPool.setMaxTotal(100);
		connPool.setDefaultMaxPerRoute(20);

		UriHttpAsyncRequestHandlerMapper handlerRegistry = new UriHttpAsyncRequestHandlerMapper();
		handlerRegistry.register("*", new ProxyRequestHandler(targetHost, executor, connPool));

		ProxyServiceHandler serviceHandler =
		                                     new ProxyServiceHandler(
		                                                             inhttpproc,
		                                                             new ProxyIncomingConnectionReuseStrategy(),
		                                                             handlerRegistry);

		final IOEventDispatch connectingEventDispatch =
		                                                new DefaultHttpClientIODispatch(
		                                                                                clientHandler,
		                                                                                ConnectionConfig.DEFAULT);

		final IOEventDispatch listeningEventDispatch =
		                                               createListeningEventDispatcher(serviceHandler);

		Thread t = new Thread(new Runnable() {

			public void run() {
				try {
					connectingIOReactor.execute(connectingEventDispatch);
				} catch (InterruptedIOException ex) {
					System.err.println("Interrupted");
				} catch (IOException ex) {
					ex.printStackTrace();
				} finally {
					try {
						listeningIOReactor.shutdown();
					} catch (IOException ex2) {
						ex2.printStackTrace();
					}
				}
			}

		});
		t.start();
		try {
			listeningIOReactor.listen(new InetSocketAddress(LOCAL_PORT));
			listeningIOReactor.execute(listeningEventDispatch);
		} catch (InterruptedIOException ex) {
			System.err.println("Interrupted");
		} catch (IOException ex) {
			ex.printStackTrace();
		} finally {
			try {
				connectingIOReactor.shutdown();
			} catch (IOException ex2) {
				ex2.printStackTrace();
			}
		}

	}

	private static IOEventDispatch createListeningEventDispatcher(ProxyServiceHandler serviceHandler)
	                                                                                                 throws KeyStoreException,
	                                                                                                 NoSuchAlgorithmException,
	                                                                                                 CertificateException,
	                                                                                                 FileNotFoundException,
	                                                                                                 IOException,
	                                                                                                 UnrecoverableKeyException,
	                                                                                                 KeyManagementException {
		IOEventDispatch listeningEventDispatch = null;
		if (SECURE_PROXY) {
			// Using the HTTPS Endpoint of the reverse proxy service.
			serverSSLContext =
			                   SSLUtil.createServerSSLContext(KEY_STORE_LOCATION,
			                                                  KEY_STORE_PASSWORD);
			listeningEventDispatch =
			                         new DefaultHttpServerIODispatch(serviceHandler,
			                                                         serverSSLContext,
			                                                         ConnectionConfig.DEFAULT);
		} else {
			// Using HTTP Endpoint of the reverse Proxy service.
			listeningEventDispatch =
			                         new DefaultHttpServerIODispatch(serviceHandler,
			                                                         ConnectionConfig.DEFAULT);
		}
		return listeningEventDispatch;
	}

	private static ProxyConnPool createConnectionPool(final ConnectingIOReactor connectingIOReactor)
	                                                                                                throws KeyManagementException,
	                                                                                                KeyStoreException,
	                                                                                                NoSuchAlgorithmException,
	                                                                                                CertificateException,
	                                                                                                FileNotFoundException,
	                                                                                                IOException {
		ProxyConnPool proxyConnPool = null;

		if (SECURE_BACKEND) {
			clientSSLContext =
			                   SSLUtil.createClientSSLContext(TRUST_STORE_LOCATION,
			                                                  TRUST_STORE_PASSWORD);

			BasicNIOConnFactory connectionFactory =
			                                        new BasicNIOConnFactory(
			                                                                clientSSLContext,
			                                                                null,
			                                                                ConnectionConfig.DEFAULT);

			proxyConnPool = new ProxyConnPool(connectingIOReactor, connectionFactory, 5000);
		} else {
			proxyConnPool = new ProxyConnPool(connectingIOReactor, ConnectionConfig.DEFAULT);
		}

		return proxyConnPool;
	}

	/**
	 * reads the properties and starts the execution environment.
	 */
	private static void init() {
		InputStream input = null;

		try {

			input = new FileInputStream("src/main/resources/config.properties");

			// load a properties file
			prop.load(input);

			LOCAL_PORT = Integer.parseInt(prop.getProperty("localPort"));

			REMOTE_HOST = String.valueOf(prop.getProperty("remoteHost"));

			REMOTE_PORT = Integer.parseInt(prop.getProperty("remotePort"));

			KEY_STORE_LOCATION = String.valueOf(prop.getProperty("keystore"));

			KEY_STORE_PASSWORD = String.valueOf(prop.getProperty("keystorepassword"));

			TRUST_STORE_LOCATION = String.valueOf(prop.getProperty("truststore"));
			TRUST_STORE_PASSWORD = String.valueOf(prop.getProperty("truststorepassword"));

			SECURE_BACKEND = Boolean.parseBoolean(prop.getProperty("secureBackend"));
			SECURE_PROXY = Boolean.parseBoolean(prop.getProperty("secureProxy"));

		} catch (IOException ex) {
			ex.printStackTrace();
		} finally {
			if (input != null) {
				try {
					input.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	static class ProxyHttpExchange {

		private final ByteBuffer inBuffer;
		private final ByteBuffer outBuffer;

		private volatile String id;
		private volatile HttpHost target;
		private volatile HttpAsyncExchange responseTrigger;
		private volatile IOControl originIOControl;
		private volatile IOControl clientIOControl;
		private volatile HttpRequest request;
		private volatile boolean requestReceived;
		private volatile HttpResponse response;
		private volatile boolean responseReceived;
		private volatile Exception ex;

		public ProxyHttpExchange() {
			super();
			this.inBuffer = ByteBuffer.allocateDirect(10240);
			this.outBuffer = ByteBuffer.allocateDirect(10240);
		}

		public ByteBuffer getInBuffer() {
			return this.inBuffer;
		}

		public ByteBuffer getOutBuffer() {
			return this.outBuffer;
		}

		public String getId() {
			return this.id;
		}

		public void setId(final String id) {
			this.id = id;
		}

		public HttpHost getTarget() {
			return this.target;
		}

		public void setTarget(final HttpHost target) {
			this.target = target;
		}

		public HttpRequest getRequest() {
			return this.request;
		}

		public void setRequest(final HttpRequest request) {
			this.request = request;
		}

		public HttpResponse getResponse() {
			return this.response;
		}

		public void setResponse(final HttpResponse response) {
			this.response = response;
		}

		public HttpAsyncExchange getResponseTrigger() {
			return this.responseTrigger;
		}

		public void setResponseTrigger(final HttpAsyncExchange responseTrigger) {
			this.responseTrigger = responseTrigger;
		}

		public IOControl getClientIOControl() {
			return this.clientIOControl;
		}

		public void setClientIOControl(final IOControl clientIOControl) {
			this.clientIOControl = clientIOControl;
		}

		public IOControl getOriginIOControl() {
			return this.originIOControl;
		}

		public void setOriginIOControl(final IOControl originIOControl) {
			this.originIOControl = originIOControl;
		}

		public boolean isRequestReceived() {
			return this.requestReceived;
		}

		public void setRequestReceived() {
			this.requestReceived = true;
		}

		public boolean isResponseReceived() {
			return this.responseReceived;
		}

		public void setResponseReceived() {
			this.responseReceived = true;
		}

		public Exception getException() {
			return this.ex;
		}

		public void setException(final Exception ex) {
			this.ex = ex;
		}

		public void reset() {
			this.inBuffer.clear();
			this.outBuffer.clear();
			this.target = null;
			this.id = null;
			this.responseTrigger = null;
			this.clientIOControl = null;
			this.originIOControl = null;
			this.request = null;
			this.requestReceived = false;
			this.response = null;
			this.responseReceived = false;
			this.ex = null;
		}

	}

	static class ProxyRequestHandler implements HttpAsyncRequestHandler<ProxyHttpExchange> {

		private final HttpHost target;
		private final HttpAsyncRequester executor;
		private final BasicNIOConnPool connPool;
		private final AtomicLong counter;

		public ProxyRequestHandler(final HttpHost target, final HttpAsyncRequester executor,
		                           final BasicNIOConnPool connPool) {
			super();
			this.target = target;
			this.executor = executor;
			this.connPool = connPool;
			this.counter = new AtomicLong(1);
		}

		public HttpAsyncRequestConsumer<ProxyHttpExchange> processRequest(final HttpRequest request,
		                                                                  final HttpContext context) {
			ProxyHttpExchange httpExchange =
			                                 (ProxyHttpExchange) context.getAttribute("http-exchange");
			if (httpExchange == null) {
				httpExchange = new ProxyHttpExchange();
				context.setAttribute("http-exchange", httpExchange);
			}
			synchronized (httpExchange) {
				httpExchange.reset();
				String id = String.format("%08X", this.counter.getAndIncrement());
				httpExchange.setId(id);
				httpExchange.setTarget(this.target);
				return new ProxyRequestConsumer(httpExchange, this.executor, this.connPool);
			}
		}

		public void handle(final ProxyHttpExchange httpExchange,
		                   final HttpAsyncExchange responseTrigger, final HttpContext context)
		                                                                                      throws HttpException,
		                                                                                      IOException {
			synchronized (httpExchange) {
				Exception ex = httpExchange.getException();
				if (ex != null) {
					ex.printStackTrace();
					System.out.println("[client<-proxy] " + httpExchange.getId() + " " + ex);
					int status = HttpStatus.SC_INTERNAL_SERVER_ERROR;
					HttpResponse response =
					                        new BasicHttpResponse(
					                                              HttpVersion.HTTP_1_1,
					                                              status,
					                                              EnglishReasonPhraseCatalog.INSTANCE.getReason(status,
					                                                                                            Locale.US));
					String message = ex.getMessage();
					if (message == null) {
						message = "Unexpected error";
					}
					response.setEntity(new NStringEntity(message, ContentType.DEFAULT_TEXT));
					responseTrigger.submitResponse(new BasicAsyncResponseProducer(response));
					System.out.println("[client<-proxy] " + httpExchange.getId() +
					                   " error response triggered");
				}
				HttpResponse response = httpExchange.getResponse();
				if (response != null) {
					responseTrigger.submitResponse(new ProxyResponseProducer(httpExchange));
					System.out.println("[client<-proxy] " + httpExchange.getId() +
					                   " response triggered");
				}
				// No response yet.
				httpExchange.setResponseTrigger(responseTrigger);
			}
		}

	}

	static class ProxyRequestConsumer implements HttpAsyncRequestConsumer<ProxyHttpExchange> {

		private final ProxyHttpExchange httpExchange;
		private final HttpAsyncRequester executor;
		private final BasicNIOConnPool connPool;

		private volatile boolean completed;

		public ProxyRequestConsumer(final ProxyHttpExchange httpExchange,
		                            final HttpAsyncRequester executor,
		                            final BasicNIOConnPool connPool) {
			super();
			this.httpExchange = httpExchange;
			this.executor = executor;
			this.connPool = connPool;
		}

		public void close() throws IOException {
		}

		public void requestReceived(final HttpRequest request) {
			synchronized (this.httpExchange) {
				System.out.println("[client->proxy] " + this.httpExchange.getId() + " " +
				                   request.getRequestLine());
				this.httpExchange.setRequest(request);
				this.executor.execute(new ProxyRequestProducer(this.httpExchange),
				                      new ProxyResponseConsumer(this.httpExchange), this.connPool);
			}
		}

		public void consumeContent(final ContentDecoder decoder, final IOControl ioctrl)
		                                                                                throws IOException {
			synchronized (this.httpExchange) {
				this.httpExchange.setClientIOControl(ioctrl);
				// Receive data from the client
				ByteBuffer buf = this.httpExchange.getInBuffer();
				int n = decoder.read(buf);
				System.out.println("[client->proxy] " + this.httpExchange.getId() + " " + n +
				                   " bytes read");
				if (decoder.isCompleted()) {
					System.out.println("[client->proxy] " + this.httpExchange.getId() +
					                   " content fully read");
				}
				// If the buffer is full, suspend client input until there is
				// free
				// space in the buffer
				if (!buf.hasRemaining()) {
					ioctrl.suspendInput();
					System.out.println("[client->proxy] " + this.httpExchange.getId() +
					                   " suspend client input");
				}
				// If there is some content in the input buffer make sure origin
				// output is active
				if (buf.position() > 0) {
					if (this.httpExchange.getOriginIOControl() != null) {
						this.httpExchange.getOriginIOControl().requestOutput();
						System.out.println("[client->proxy] " + this.httpExchange.getId() +
						                   " request origin output");
					}
				}
			}
		}

		public void requestCompleted(final HttpContext context) {
			synchronized (this.httpExchange) {
				this.completed = true;;
				System.out.println("[client->proxy] " + this.httpExchange.getId() +
				                   " request completed");
				this.httpExchange.setRequestReceived();
				if (this.httpExchange.getOriginIOControl() != null) {
					this.httpExchange.getOriginIOControl().requestOutput();
					System.out.println("[client->proxy] " + this.httpExchange.getId() +
					                   " request origin output");
				}
			}
		}

		public Exception getException() {
			return null;
		}

		public ProxyHttpExchange getResult() {
			return this.httpExchange;
		}

		public boolean isDone() {
			return this.completed;
		}

		public void failed(final Exception ex) {
			System.out.println("[client->proxy] " + ex.toString());
		}

	}

	static class ProxyRequestProducer implements HttpAsyncRequestProducer {

		private final ProxyHttpExchange httpExchange;

		public ProxyRequestProducer(final ProxyHttpExchange httpExchange) {
			super();
			this.httpExchange = httpExchange;
		}

		public void close() throws IOException {
		}

		public HttpHost getTarget() {
			synchronized (this.httpExchange) {
				return this.httpExchange.getTarget();
			}
		}

		public HttpRequest generateRequest() {
			synchronized (this.httpExchange) {
				HttpRequest request = this.httpExchange.getRequest();
				System.out.println("[proxy->origin] " + this.httpExchange.getId() + " " +
				                   request.getRequestLine());
				// Rewrite request!!!!
				if (request instanceof HttpEntityEnclosingRequest) {
					BasicHttpEntityEnclosingRequest r =
					                                    new BasicHttpEntityEnclosingRequest(
					                                                                        request.getRequestLine());
					r.setEntity(((HttpEntityEnclosingRequest) request).getEntity());
					return r;
				} else {
					return new BasicHttpRequest(request.getRequestLine());
				}
			}
		}

		public void produceContent(final ContentEncoder encoder, final IOControl ioctrl)
		                                                                                throws IOException {
			synchronized (this.httpExchange) {
				this.httpExchange.setOriginIOControl(ioctrl);
				// Send data to the origin server
				ByteBuffer buf = this.httpExchange.getInBuffer();
				buf.flip();
				int n = encoder.write(buf);
				buf.compact();
				System.out.println("[proxy->origin] " + this.httpExchange.getId() + " " + n +
				                   " bytes written");
				// If there is space in the buffer and the message has not been
				// transferred, make sure the client is sending more data
				if (buf.hasRemaining() && !this.httpExchange.isRequestReceived()) {
					if (this.httpExchange.getClientIOControl() != null) {
						this.httpExchange.getClientIOControl().requestInput();
						System.out.println("[proxy->origin] " + this.httpExchange.getId() +
						                   " request client input");
					}
				}
				if (buf.position() == 0) {
					if (this.httpExchange.isRequestReceived()) {
						encoder.complete();
						System.out.println("[proxy->origin] " + this.httpExchange.getId() +
						                   " content fully written");
					} else {
						// Input buffer is empty. Wait until the client fills up
						// the buffer
						ioctrl.suspendOutput();
						System.out.println("[proxy->origin] " + this.httpExchange.getId() +
						                   " suspend origin output");
					}
				}
			}
		}

		public void requestCompleted(final HttpContext context) {
			synchronized (this.httpExchange) {
				System.out.println("[proxy->origin] " + this.httpExchange.getId() +
				                   " request completed");
			}
		}

		public boolean isRepeatable() {
			return false;
		}

		public void resetRequest() {
		}

		public void failed(final Exception ex) {
			System.out.println("[proxy->origin] " + ex.toString());
		}

	}

	static class ProxyResponseConsumer implements HttpAsyncResponseConsumer<ProxyHttpExchange> {

		private final ProxyHttpExchange httpExchange;

		private volatile boolean completed;

		public ProxyResponseConsumer(final ProxyHttpExchange httpExchange) {
			super();
			this.httpExchange = httpExchange;
		}

		public void close() throws IOException {
		}

		public void responseReceived(final HttpResponse response) {
			synchronized (this.httpExchange) {
				System.out.println("[proxy<-origin] " + this.httpExchange.getId() + " " +
				                   response.getStatusLine());
				this.httpExchange.setResponse(response);
				HttpAsyncExchange responseTrigger = this.httpExchange.getResponseTrigger();
				if (responseTrigger != null && !responseTrigger.isCompleted()) {
					System.out.println("[client<-proxy] " + this.httpExchange.getId() +
					                   " response triggered");
					responseTrigger.submitResponse(new ProxyResponseProducer(this.httpExchange));
				}
			}
		}

		public void consumeContent(final ContentDecoder decoder, final IOControl ioctrl)
		                                                                                throws IOException {
			synchronized (this.httpExchange) {
				this.httpExchange.setOriginIOControl(ioctrl);
				// Receive data from the origin
				ByteBuffer buf = this.httpExchange.getOutBuffer();
				int n = decoder.read(buf);
				System.out.println("[proxy<-origin] " + this.httpExchange.getId() + " " + n +
				                   " bytes read");
				if (decoder.isCompleted()) {
					System.out.println("[proxy<-origin] " + this.httpExchange.getId() +
					                   " content fully read");
				}
				// If the buffer is full, suspend origin input until there is
				// free
				// space in the buffer
				if (!buf.hasRemaining()) {
					ioctrl.suspendInput();
					System.out.println("[proxy<-origin] " + this.httpExchange.getId() +
					                   " suspend origin input");
				}
				// If there is some content in the input buffer make sure client
				// output is active
				if (buf.position() > 0) {
					if (this.httpExchange.getClientIOControl() != null) {
						this.httpExchange.getClientIOControl().requestOutput();
						System.out.println("[proxy<-origin] " + this.httpExchange.getId() +
						                   " request client output");
					}
				}
			}
		}

		public void responseCompleted(final HttpContext context) {
			synchronized (this.httpExchange) {
				if (this.completed) {
					return;
				}
				this.completed = true;
				System.out.println("[proxy<-origin] " + this.httpExchange.getId() +
				                   " response completed");
				this.httpExchange.setResponseReceived();
				if (this.httpExchange.getClientIOControl() != null) {
					this.httpExchange.getClientIOControl().requestOutput();
					System.out.println("[proxy<-origin] " + this.httpExchange.getId() +
					                   " request client output");
				}
			}
		}

		public void failed(final Exception ex) {
			synchronized (this.httpExchange) {
				if (this.completed) {
					return;
				}
				this.completed = true;
				this.httpExchange.setException(ex);
				HttpAsyncExchange responseTrigger = this.httpExchange.getResponseTrigger();
				if (responseTrigger != null && !responseTrigger.isCompleted()) {
					System.out.println("[client<-proxy] " + this.httpExchange.getId() + " " + ex);
					int status = HttpStatus.SC_INTERNAL_SERVER_ERROR;
					HttpResponse response =
					                        new BasicHttpResponse(
					                                              HttpVersion.HTTP_1_1,
					                                              status,
					                                              EnglishReasonPhraseCatalog.INSTANCE.getReason(status,
					                                                                                            Locale.US));
					String message = ex.getMessage();
					if (message == null) {
						message = "Unexpected error";
					}
					response.setEntity(new NStringEntity(message, ContentType.DEFAULT_TEXT));
					responseTrigger.submitResponse(new BasicAsyncResponseProducer(response));
				}
			}
		}

		public boolean cancel() {
			synchronized (this.httpExchange) {
				if (this.completed) {
					return false;
				}
				failed(new InterruptedIOException("Cancelled"));
				return true;
			}
		}

		public ProxyHttpExchange getResult() {
			return this.httpExchange;
		}

		public Exception getException() {
			return null;
		}

		public boolean isDone() {
			return this.completed;
		}

	}

	static class ProxyResponseProducer implements HttpAsyncResponseProducer {

		private final ProxyHttpExchange httpExchange;

		public ProxyResponseProducer(final ProxyHttpExchange httpExchange) {
			super();
			this.httpExchange = httpExchange;
		}

		public void close() throws IOException {
			this.httpExchange.reset();
		}

		public HttpResponse generateResponse() {
			synchronized (this.httpExchange) {
				HttpResponse response = this.httpExchange.getResponse();
				System.out.println("[client<-proxy] " + this.httpExchange.getId() + " " +
				                   response.getStatusLine());
				// Rewrite response!!!!
				BasicHttpResponse r = new BasicHttpResponse(response.getStatusLine());
				r.setEntity(response.getEntity());
				return r;
			}
		}

		public void produceContent(final ContentEncoder encoder, final IOControl ioctrl)
		                                                                                throws IOException {
			synchronized (this.httpExchange) {
				this.httpExchange.setClientIOControl(ioctrl);
				// Send data to the client
				ByteBuffer buf = this.httpExchange.getOutBuffer();
				buf.flip();
				int n = encoder.write(buf);
				buf.compact();
				System.out.println("[client<-proxy] " + this.httpExchange.getId() + " " + n +
				                   " bytes written");
				// If there is space in the buffer and the message has not been
				// transferred, make sure the origin is sending more data
				if (buf.hasRemaining() && !this.httpExchange.isResponseReceived()) {
					if (this.httpExchange.getOriginIOControl() != null) {
						this.httpExchange.getOriginIOControl().requestInput();
						System.out.println("[client<-proxy] " + this.httpExchange.getId() +
						                   " request origin input");
					}
				}
				if (buf.position() == 0) {
					if (this.httpExchange.isResponseReceived()) {
						encoder.complete();
						System.out.println("[client<-proxy] " + this.httpExchange.getId() +
						                   " content fully written");
					} else {
						// Input buffer is empty. Wait until the origin fills up
						// the buffer
						ioctrl.suspendOutput();
						System.out.println("[client<-proxy] " + this.httpExchange.getId() +
						                   " suspend client output");
					}
				}
			}
		}

		public void responseCompleted(final HttpContext context) {
			synchronized (this.httpExchange) {
				System.out.println("[client<-proxy] " + this.httpExchange.getId() +
				                   " response completed");
			}
		}

		public void failed(final Exception ex) {
			System.out.println("[client<-proxy] " + ex.toString());
		}

	}

	static class ProxyIncomingConnectionReuseStrategy extends DefaultConnectionReuseStrategy {

		@Override
		public boolean keepAlive(final HttpResponse response, final HttpContext context) {
			NHttpConnection conn =
			                       (NHttpConnection) context.getAttribute(HttpCoreContext.HTTP_CONNECTION);
			boolean keepAlive = super.keepAlive(response, context);
			if (keepAlive) {
				System.out.println("[client->proxy] connection kept alive " + conn);
			}
			return keepAlive;
		}

	};

	static class ProxyOutgoingConnectionReuseStrategy extends DefaultConnectionReuseStrategy {

		@Override
		public boolean keepAlive(final HttpResponse response, final HttpContext context) {
			NHttpConnection conn =
			                       (NHttpConnection) context.getAttribute(HttpCoreContext.HTTP_CONNECTION);
			boolean keepAlive = super.keepAlive(response, context);
			if (keepAlive) {
				System.out.println("[proxy->origin] connection kept alive " + conn);
			}
			return keepAlive;
		}

	};

	static class ProxyServiceHandler extends HttpAsyncService {

		public ProxyServiceHandler(final HttpProcessor httpProcessor,
		                           final ConnectionReuseStrategy reuseStrategy,
		                           final HttpAsyncRequestHandlerMapper handlerResolver) {
			super(httpProcessor, reuseStrategy, null, handlerResolver, null);
		}

		@Override
		protected void log(final Exception ex) {
			ex.printStackTrace();
		}

		@Override
		public void connected(final NHttpServerConnection conn) {
			System.out.println("[client->proxy] connection open " + conn);
			super.connected(conn);
		}

		@Override
		public void closed(final NHttpServerConnection conn) {
			System.out.println("[client->proxy] connection closed " + conn);
			super.closed(conn);
		}

	}

	static class ProxyClientProtocolHandler extends HttpAsyncRequestExecutor {

		public ProxyClientProtocolHandler() {
			super();
		}

		@Override
		protected void log(final Exception ex) {
			ex.printStackTrace();
		}

		@Override
		public void connected(final NHttpClientConnection conn, final Object attachment)
		                                                                                throws IOException,
		                                                                                HttpException {
			System.out.println("[proxy->origin] connection open " + conn);
			super.connected(conn, attachment);
		}

		@Override
		public void closed(final NHttpClientConnection conn) {
			System.out.println("[proxy->origin] connection closed " + conn);
			super.closed(conn);
		}

	}

	static class ProxyConnPool extends BasicNIOConnPool {

		public ProxyConnPool(final ConnectingIOReactor ioreactor, final ConnectionConfig config) {
			super(ioreactor, config);
		}

		public ProxyConnPool(final ConnectingIOReactor ioreactor, final NIOConnFactory connFactory,
		                     final int connectTimeout) {
			super(ioreactor, connFactory, connectTimeout);
		}

		@Override
		public void release(final BasicNIOPoolEntry entry, boolean reusable) {
			System.out.println("[proxy->origin] connection released " + entry.getConnection());
			super.release(entry, reusable);
			StringBuilder buf = new StringBuilder();
			PoolStats totals = getTotalStats();
			buf.append("[total kept alive: ").append(totals.getAvailable()).append("; ");
			buf.append("total allocated: ").append(totals.getLeased() + totals.getAvailable());
			buf.append(" of ").append(totals.getMax()).append("]");
			System.out.println("[proxy->origin] " + buf.toString());
		}

	}

}
