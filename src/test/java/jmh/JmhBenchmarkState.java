package jmh;

import hudson.model.Hudson;
import jenkins.model.Jenkins;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.security.Password;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.jvnet.hudson.test.NoListenerConfiguration;
import org.jvnet.hudson.test.ThreadPoolImpl;
import org.jvnet.hudson.test.WarExploder;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import javax.servlet.ServletContext;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@State(Scope.Benchmark)
public abstract class JmhBenchmarkState {
    private static final Logger LOGGER = Logger.getLogger(JmhBenchmarkState.class.getName());
    private static final String contextPath = "/jenkins";

    private final File baseTempDirectory = new File(System.getProperty("java.io.tmpdir"));
    private final boolean withoutSpace = Boolean.getBoolean("jenkins.test.noSpaceInTmpDirs");

    private Jenkins jenkins = null;
    private Server server = null;

    // Run the setup for each individual fork of the JVM
    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    public final void setupJenkins() throws Exception {
        jenkins = launchInstance();
    }

    // Run the tearDown for each individual fork of the JVM
    @TearDown(org.openjdk.jmh.annotations.Level.Trial)
    public void tearDown() throws Exception {
        if (jenkins != null && server != null) {
            server.stop();
            jenkins.cleanUp();
            jenkins = null;
            server = null;
        }
    }

    private Jenkins launchInstance() throws Exception {
        ServletContext webServer = createServer();
        File jenkinsHome = newTemporaryJenkinsHome();
        return new Hudson(jenkinsHome, webServer);
    }

    private ServletContext createServer() throws Exception {
        server = new Server(new ThreadPoolImpl(new ThreadPoolExecutor(10, 10,
                10L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r);
            t.setName("Jetty Thread Pool");
            return t;
        })));

        WebAppContext context = new WebAppContext(WarExploder.getExplodedDir().getPath(), contextPath);
        context.setClassLoader(getClass().getClassLoader());
        context.setConfigurations(new Configuration[]{new WebXmlConfiguration()});
        context.addBean(new NoListenerConfiguration(context));
        server.setHandler(context);

        final MimeTypes mimeTypes = new MimeTypes();
        mimeTypes.addMimeMapping("js", "application/javascript");

        context.setMimeTypes(mimeTypes);
        context.getSecurityHandler().setLoginService(configureUserRealm());
        context.setResourceBase(WarExploder.getExplodedDir().getPath());

        ServerConnector connector = new ServerConnector(server, 1, 1);
        HttpConfiguration config = connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration();
        // use a bigger buffer as Stapler traces can get pretty large on deeply nested URL
        config.setRequestHeaderSize(12 * 1024);
        connector.setHost("localhost");
        if (System.getProperty("port") != null)
            connector.setPort(Integer.parseInt(System.getProperty("port")));

        server.addConnector(connector);
        server.start();

        int localPort = connector.getLocalPort();
        URL jenkinsURL = new URL("http://localhost:" + localPort + contextPath + "/");
        LOGGER.log(Level.INFO, "Running on {0}", jenkinsURL);

        return context.getServletContext();
    }

    private LoginService configureUserRealm() {
        HashLoginService realm = new HashLoginService();
        realm.setName("default");   // this is the magic realm name to make it effective on everywhere
        UserStore userStore = new UserStore();
        realm.setUserStore(userStore);
        userStore.addUser("alice", new Password("alice"), new String[]{"user", "female"});
        userStore.addUser("bob", new Password("bob"), new String[]{"user", "male"});
        userStore.addUser("charlie", new Password("charlie"), new String[]{"user", "male"});
        return realm;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private synchronized File newTemporaryJenkinsHome() throws IOException {
        try {
            File f = File.createTempFile((withoutSpace ? "jkh" : "j h"), "", baseTempDirectory);
            f.delete();
            f.mkdirs();
            f.deleteOnExit();
            return f;
        } catch (IOException e) {
            throw new IOException("Failed to create a temporary directory in " + baseTempDirectory, e);
        }
    }

    public Jenkins getJenkins() {
        return jenkins;
    }
}
