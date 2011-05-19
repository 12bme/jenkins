package metanectar.provisioning;

import com.cloudbees.commons.metanectar.provisioning.ComputerLauncherFactory;
import com.cloudbees.commons.metanectar.provisioning.FutureComputerLauncherFactory;
import com.cloudbees.commons.metanectar.provisioning.SlaveManager;
import hudson.model.Hudson;
import hudson.model.Hudson.MasterComputer;
import hudson.model.Label;
import hudson.model.Node.Mode;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.remoting.Channel;
import hudson.remoting.FastPipedInputStream;
import hudson.remoting.FastPipedOutputStream;
import hudson.remoting.RemoteFuture;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;
import hudson.util.StreamTaskListener;
import hudson.util.VariableResolver;
import metanectar.test.MetaNectarTestCase;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * @author Kohsuke Kawaguchi
 */
public class MetaNectarSlaveManagerTest extends MetaNectarTestCase {
    /**
     * Represents the MetaNectar end of the channel.
     */
    protected Channel metaNectar;
    /**
     * Represents the Jenkins end of the channel.
     */
    protected Channel jenkins;
    private ExecutorService executors = Executors.newCachedThreadPool();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        final FastPipedInputStream p1i = new FastPipedInputStream();
        final FastPipedInputStream p2i = new FastPipedInputStream();
        final FastPipedOutputStream p1o = new FastPipedOutputStream(p1i);
        final FastPipedOutputStream p2o = new FastPipedOutputStream(p2i);

        Future<Channel> f1 = executors.submit(new Callable<Channel>() {
            public Channel call() throws Exception {
                return new Channel("This side of the channel", executors, p1i, p2o);
            }
        });
        Future<Channel> f2 = executors.submit(new Callable<Channel>() {
            public Channel call() throws Exception {
                return new Channel("The other side of the channel", executors, p2i, p1o);
            }
        });
        metaNectar = f1.get();
        jenkins = f2.get();
    }

    @Override
    protected void tearDown() throws Exception {
        metaNectar.close(); // this will automatically initiate the close on the other channel, too.
        metaNectar.join();
        jenkins.join();
        executors.shutdownNow();
    }

    public void testScenario() throws Exception {
        // MetaNectar would expose the manager to the channel
        metaNectar.setProperty(SlaveManager.class.getName(),
                metaNectar.export(SlaveManager.class,new DummyMetaNectarSlaveManagerImpl()));

        // Jenkins would obtain a proxy
        SlaveManager proxy = (SlaveManager)jenkins.waitForRemoteProperty(SlaveManager.class.getName());
        assertFalse("we are accesing it via a proxy, and not directly", proxy instanceof DummyMetaNectarSlaveManagerImpl);

        // this dummy manager can allocate 'foo', so it can provision "foo||bar"
        final Label fooOrBar = Label.parseExpression("foo||bar");
        assertTrue(proxy.canProvision(fooOrBar));

        StreamTaskListener stl = new StreamTaskListener(new OutputStreamWriter(System.out));
        final FutureComputerLauncherFactory pip = proxy.provision(fooOrBar, stl, 1);
        System.out.println("J: Waiting for the provisioning of " + pip.getDisplayName());
        final ComputerLauncherFactory r = pip.get();
        System.out.println("J: Result obtained");
        final ComputerLauncher l = r.getOrCreateLauncher();
        System.out.println(l);

        DumbSlave slave = new DumbSlave("slave1", "dummy",
                createTmpDir().getPath(), "1", Mode.NORMAL, "foo", l, RetentionStrategy.NOOP, Collections.EMPTY_LIST);
        hudson.addNode(slave);
        System.out.println("Connecting to the provisioned slave");
        slave.toComputer().connect(true).get();
        System.out.println("Tearing it down");
        slave.toComputer().disconnect();
    }

    public static class DummyMetaNectarSlaveManagerImpl implements SlaveManager {
        private int n;

        public boolean canProvision(Label label) throws IOException, InterruptedException {
            return label.matches(new VariableResolver<Boolean>() {
                public Boolean resolve(String name) {
                    return name.equals("foo");
                }
            });
        }

        public Collection<LabelAtom> getLabels() {
            return Collections.singleton(new LabelAtom("foo"));
        }

        public FutureComputerLauncherFactory provision(Label label, final TaskListener listener, int numOfExecutors) throws IOException, InterruptedException {
            listener.getLogger().println("MN: Started provisioning");
            final Future<ComputerLauncherFactory> task = MasterComputer.threadPoolForRemoting.submit(new Callable<ComputerLauncherFactory>() {
                public ComputerLauncherFactory call() throws Exception {
                    Thread.sleep(3000);
                    listener.getLogger().println("MN: Still provisioning");
                    Thread.sleep(3000);
                    listener.getLogger().println("MN: Done provisioning");
                    return new ResultImpl();
                }
            });

            return new FutureComputerLauncherFactory("slave"+(n++), 1, new RemoteFuture<ComputerLauncherFactory>(task));
        }

        private static class ResultImpl extends ComputerLauncherFactory {

            public ComputerLauncher getOrCreateLauncher() throws IOException, InterruptedException {
                try {
                    return new CommandLauncher(
                            String.format("\"%s/bin/java\" -jar \"%s\"",
                                    System.getProperty("java.home"),
                                    new File(Hudson.getInstance().getJnlpJars("slave.jar").getURL().toURI()).getAbsolutePath()));
                } catch (URISyntaxException e) {
                    // during the test we always find slave.jar in the file system
                    throw new AssertionError(e);
                }
            }
        }
    }
}
