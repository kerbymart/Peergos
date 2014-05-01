package peergos.storage.net;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.Creator;
import akka.japi.pf.FI;
import akka.japi.pf.ReceiveBuilder;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import peergos.crypto.SSL;
import peergos.storage.dht.Letter;
import peergos.storage.dht.Message;
import peergos.storage.Storage;
import org.bouncycastle.operator.OperatorCreationException;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HttpsMessenger extends AbstractActor
{
    public static final String MESSAGE_URL = "/message/";
    public static final String USER_URL = "/user/";

    public static final int THREADS = 100;
    public static final int CONNECTION_BACKLOG = 100;

    private final Logger LOGGER;
    private final int localPort;
    HttpsServer httpsServer;
    private PartialFunction<Object, BoxedUnit> ready;

    public HttpsMessenger(int port, Logger LOGGER) throws IOException
    {
        this.LOGGER = LOGGER;
        this.localPort = port;
        ready = ReceiveBuilder.match(Letter.class, new FI.UnitApply<Letter>() {
            @Override
            public void apply(Letter p) throws Exception {
                if (p.dest == null)
                    sender().tell(new JOINED(), self());
                else
                    sendMessage(p.m, p.dest, p.destPort);
            }
        }).build();

        receive(ReceiveBuilder.match(INITIALIZE.class, new FI.UnitApply<INITIALIZE>() {
            @Override
            public void apply(INITIALIZE j) throws Exception {
                if (init(sender(), context().system()))
                {
                    context().become(ready);
                    sender().tell(new INITIALIZED(), self());
                }
                else
                {
                    sender().tell(new INITERROR(), self());
                }
            }
        }).build());
    }

    public static Props props(final int port, final Logger LOGGER)
    {
        return Props.create(HttpsMessenger.class, new Creator<HttpsMessenger>() {
            @Override
            public HttpsMessenger create() throws Exception {
                return new HttpsMessenger(port, LOGGER);
            }
        });
    }

    public boolean init(ActorRef router, ActorSystem system) throws IOException {
        try
        {
            InetAddress us = IP.getMyPublicAddress();
            InetSocketAddress address = new InetSocketAddress(us, localPort);
            System.out.println("Starting user API server at: " + us.getHostAddress() + ":" + localPort);
            httpsServer = HttpsServer.create(address, CONNECTION_BACKLOG);
            SSLContext sslContext = SSLContext.getInstance("TLS");

            char[] password = "storage".toCharArray();
            KeyStore ks = SSL.getKeyStore(password);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, password);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ks);

            // setup the HTTPS context and parameters
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            SSLContext.setDefault(sslContext);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext)
            {
                public void configure(HttpsParameters params)
                {
                    try
                    {
                        // initialise the SSL context
                        SSLContext c = SSLContext.getDefault();
                        SSLEngine engine = c.createSSLEngine();
                        params.setNeedClientAuth(false);
                        params.setCipherSuites(engine.getEnabledCipherSuites());
                        params.setProtocols(engine.getEnabledProtocols());

                        // get the default parameters
                        SSLParameters defaultSSLParameters = c.getDefaultSSLParameters();
                        params.setSSLParameters(defaultSSLParameters);
                    }
                    catch (Exception ex)
                    {
                        System.err.println("Failed to create HTTPS port");
                        ex.printStackTrace(System.err);
                    }
                }
            } );
        }
        catch (NoSuchAlgorithmException|InvalidKeyException|KeyStoreException|CertificateException|
                NoSuchProviderException|SignatureException|OperatorCreationException|
                UnrecoverableKeyException|KeyManagementException ex)
        {
            System.err.println("Failed to create HTTPS port");
            ex.printStackTrace(System.err);
            return false;
        }

        httpsServer.createContext(USER_URL, new HttpUserAPIHandler(router, system));
        httpsServer.setExecutor(Executors.newFixedThreadPool(THREADS));
        httpsServer.start();

        return true;
    }

    // need to think about latency of opening all these SSL connections,
    // maybe we could keep open connections to neighbours, and only open to distant nodes in the DHT?
    public void sendMessage(Message m, InetAddress addr, int port) throws IOException
    {
        if (Message.LOG)
            LOGGER.log(Level.ALL, String.format("Sent %s with target %d to %s:%d\n", m.name(), m.getTarget(), addr, port));
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        m.write(new DataOutputStream(bout));
        URL target = new URL("https", addr.getHostAddress(), port, MESSAGE_URL);
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        LOGGER.log(Level.ALL, String.format("prior to get output stream"));
        OutputStream out = conn.getOutputStream();
        LOGGER.log(Level.ALL, String.format("post to get output stream"));
        out.write(bout.toByteArray());
        out.flush();
        out.close();
        conn.getResponseCode();
        LOGGER.log(Level.ALL, String.format("Finished sending %s with target %d to %s:%d\n", m.name(), m.getTarget(), addr, port));
    }

    public static byte[] getFragment(InetAddress addr, int port, String key) throws IOException
    {
        // for now, make a direct connection
        URL target = new URL("https", addr.getHostAddress(), port, key);
        System.out.println("getting fragment from " + addr.getHostAddress());
        URLConnection conn = target.openConnection();
        InputStream in = conn.getInputStream();
        byte[] buf = new byte[2*1024*1024];
        ByteArrayOutputStream bout = new ByteArrayOutputStream();

        while (true)
        {
            int r = in.read(buf);
            if (r < 0)
                break;
            bout.write(buf, 0, r);
        }
        return bout.toByteArray();
    }

    public static void putFragment(InetAddress addr, int port, String key, byte[] value) throws IOException
    {
        // for now, make a direct connection
        URL target = new URL("https", addr.getHostAddress(), port, key+"/");
        System.out.println("sending fragment to " + target.toString());
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        OutputStream out = conn.getOutputStream();
        out.write(value);
        out.flush();
        out.close();
        conn.getResponseCode();
    }

    public static class JOIN
    {
        public final InetAddress addr;
        public final int port;

        public JOIN(InetAddress addr, int port)
        {
            this.addr = addr;
            this.port = port;
        }
    }

    public static class JOINED {}
    public static class JOINERROR {}

    public static class INITIALIZE {}
    public static class INITIALIZED {}
    public static class INITERROR {}
}