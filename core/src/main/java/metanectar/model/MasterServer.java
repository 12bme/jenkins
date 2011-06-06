package metanectar.model;

import com.cloudbees.commons.metanectar.provisioning.SlaveManager;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;
import hudson.Extension;
import hudson.Util;
import hudson.console.AnnotatedLargeText;
import hudson.model.*;
import hudson.remoting.Channel;
import hudson.util.DescribableList;
import hudson.util.RemotingDiagnostics;
import hudson.util.StreamTaskListener;
import hudson.util.io.ReopenableFileOutputStream;
import metanectar.Config;
import metanectar.provisioning.IdentifierFinder;
import metanectar.provisioning.MetaNectarSlaveManager;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static metanectar.model.MasterServer.State.*;
import static metanectar.model.MasterServer.State.Approved;

/**
 * Representation of remote master inside MetaNectar.
 *
 * @author Kohsuke Kawaguchi, Paul Sandoz
 */
public class MasterServer extends AbstractItem implements TopLevelItem {

    public static IdentifierFinder MASTER_SERVER_IDENTIFIER_FINDER = new IdentifierFinder(new IdentifierFinder.Identifier() {
        public int getId(MasterServer ms) {
            return ms.getId();
        }
    });

    public static IdentifierFinder NODE_IDENTIFIER_FINDER = new IdentifierFinder(new IdentifierFinder.Identifier() {
        public int getId(MasterServer ms) {
            return ms.getNodeId();
        }
    });


    /**
     * The states of the master.
     */
    public static enum State {
        Created(Action.Provision, Action.Delete),
        PreProvisioning(),
        Provisioning(),
        ProvisioningErrorNoResources(),   // TODO delete
        ProvisioningError(Action.Provision, Action.Terminate),
        Provisioned(Action.Start, Action.Terminate),
        Starting(),
        StartingError(Action.Start, Action.Stop),
        Started(Action.Stop),
        ApprovalError(Action.Stop),
        Approved(Action.Stop),
        Stopping(),
        StoppingError(Action.Stop, Action.Terminate),
        Stopped(Action.Start, Action.Terminate),
        Terminating(),
        TerminatingError(Action.Terminate, Action.Delete),
        Terminated(Action.Provision, Action.Delete);

        public ImmutableSet<Action> actions;

        State(Action... actions) {
            this.actions = new ImmutableSet.Builder<Action>().add(actions).build();
        }

        public boolean canDo(Action a) {
            return actions.contains(a);
        }
    }

    /**
     * Actions that can be performed on a master.
     */
    public static enum Action {
        Provision("new-computer.png"),
        Start("start-computer.png"),
        Stop("stop-computer.png"),
        Terminate("terminate-computer.png"),
        Delete("trash-computer.png");

        public final String icon;

        public final String displayName;

        public final String href;

        Action(String icon) {
            this.icon = icon;
            this.displayName = name();
            this.href = name().toLowerCase();
        }

        Action(String icon, String displayName) {
            this.icon = icon;
            this.displayName = displayName;
            this.href = name().toLowerCase();
        }
    }

    /**
     * The state of the master.
     */
    private volatile State state;

    /**
     * A unique number that is always less than the total number of masters created.
     */
    private int id;

    /**
     * The name encoded for safe use within URL path segments.
     */
    private String encodedName;

    /**
     * A unique name comprising of the id and name. This is suitable for use as a master home directory name.
     * Any unsafe characters in the name will be encoded.
     */
    private String idName;

    /**
     * The time stamp when the state was modified.
     *
     * @see {@link java.util.Date#getTime()}.
     */
    private volatile long timeStamp;

    /**
     * Error associated with a particular state.
     */
    private transient volatile Throwable error;

    /**
     * The grant ID for the master to validate when initially connecting.
     */
    private String grantId;

    /**
     * If the connection to this master is approved, set to true.
     */
    private volatile boolean approved;

    /**
     * The name of the node where the master is provisioned
     */
    private volatile String nodeName;

    /**
     * The node where this masters is provisioned.
     * <p>
     * Only the node name is serialized.
     */
    private transient volatile Node node;

    /**
     * A unique number that is always less than the total number of masters
     * provisioned for a node.
     */
    private volatile int nodeId;

    /**
     * The local home directory of the master.
     */
    private volatile String localHome;

    /**
     * The local URL to the master.
     */
    private volatile URL localEndpoint;

    /**
     * The global URL to the master. May be null if no reverse proxy is utilized.
     */
    private volatile URL globalEndpoint;

    /**
     * The encoded image of the public key that indicates the identity of the master.
     */
    private volatile byte[] identity;

    /**
     * The URL pointing to the snapshot of the home directory of the master.
     */
    private volatile URL snapshot;

    // connected state

    private transient volatile Channel channel;

    private transient MetaNectarSlaveManager slaveManager;

    /**
     * Perpetually writable log file.
     */
    private transient ReopenableFileOutputStream log;

    /**
     * {@link TaskListener} that wraps {@link #log}, hence perpetually writable.
     */
    private transient TaskListener taskListener;

    private volatile DescribableList<MasterServerProperty<?>,MasterServerPropertyDescriptor> properties =
            new PropertyList(this);

    protected MasterServer(ItemGroup parent, String name) {
        super(parent, name);

    }

    @Override
    public void onLoad(ItemGroup<? extends Item> parent, String name)
            throws IOException {
        super.onLoad(parent, name);
        init();
    }

    @Override
    public void onCreatedFromScratch() {
        super.onCreatedFromScratch();
        init();

        try {
            setCreatedState(MasterServer.MASTER_SERVER_IDENTIFIER_FINDER.getUnusedIdentifier(Hudson.getInstance().getAllItems(MasterServer.class)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void init() {
        log = new ReopenableFileOutputStream(getLogFile());
        taskListener = new StreamTaskListener(log);
    }


    // Logging

    private File getLogFile() {
        return new File(getRootDir(),"log.txt");
    }

    public String getLog() throws IOException {
        return Util.loadFile(getLogFile());
    }

    public AnnotatedLargeText<TopLevelItem> getLogText() {
        return new AnnotatedLargeText<TopLevelItem>(getLogFile(), Charset.defaultCharset(), false, this);
    }

    public void doProgressiveLog( StaplerRequest req, StaplerResponse rsp) throws IOException {
        getLogText().doProgressText(req,rsp);
    }

    //

    public String toString() {
        final RSAPublicKey key = getIdentity();
        return Objects.toStringHelper(this).
                add("state", state).
                add("id", id).
                add("encodedName", name).
                add("idName", idName).
                add("timeStamp", timeStamp).
                add("error", error).
                add("grantId", grantId).
                add("approved", approved).
                add("nodeName", nodeName).
                add("node", getNode()).
                add("nodeId", nodeId).
                add("localHome", localHome).
                add("localEndpoint", localEndpoint).
                add("globalEndpoint", globalEndpoint).
                add("snapshot", snapshot).
                add("channel", channel).
                add("identity", (key == null) ? null : key.getFormat() + ", " + key.getAlgorithm()).
                toString();
    }

    /**
     * No nested job under Jenkins server
     *
     * @deprecated
     *      No one shouldn't be calling this directly.
     */
    @Override
    public final Collection<? extends Job> getAllJobs() {
        return Collections.emptyList();
    }

    public TopLevelItemDescriptor getDescriptor() {
        return (TopLevelItemDescriptor) Hudson.getInstance().getDescriptorOrDie(getClass());
    }


    // Methods for modifying state

    public synchronized void setCreatedState(int id) throws IOException {
        setState(Created);
        this.id = id;
        this.encodedName = createEncodedName(name);
        this.idName = createIdName(id, encodedName);

        save();
        fireOnStateChange();

        taskListener.getLogger().println("Created");
        taskListener.getLogger().println(toString());
    }

    public synchronized void setPreProvisionState() throws IOException {
        setState(PreProvisioning);
        this.grantId = createGrant();
        save();
        fireOnStateChange();

        taskListener.getLogger().println("PreProvisioning");
        taskListener.getLogger().println(toString());
    }

    private String createGrant() {
        return UUID.randomUUID().toString();
    }

    public synchronized void setProvisionStartedState(Node node, int id) throws IOException {
        setState(Provisioning);
        this.nodeName = node.getNodeName();
        this.node = node;
        this.nodeId = id;
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Provisioning");
        taskListener.getLogger().println(toString());
    }

    public synchronized void setProvisionCompletedState(String home, URL endpoint) throws IOException {
        setState(Provisioned);
        this.localHome = home;
        this.localEndpoint = endpoint;
        this.globalEndpoint = createGlobalEndpoint(endpoint);
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Provisioned");
        taskListener.getLogger().println(toString());
    }

    private static URL createGlobalEndpoint(URL localEndpoint) throws IOException {
        Config.ProxyProperties p = MetaNectar.getInstance().getConfig().getBean(Config.ProxyProperties.class);
        if (p.getBaseEndpoint() != null) {
            URL proxyEndpoint = p.getBaseEndpoint();

            // This assumes that the paths for both URLs start with "/"
            String path = proxyEndpoint.getPath() + localEndpoint.getPath();
            path = path.replaceAll("/+", "/");
            return new URL(proxyEndpoint.getProtocol(), proxyEndpoint.getHost(), proxyEndpoint.getPort(), path);
        } else {
            return null;
        }
    }

    public synchronized void setProvisionErrorState(Throwable error) throws IOException {
        setState(ProvisioningError);
        this.error = error;
        this.nodeId = 0;
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Provision Error");
        taskListener.getLogger().println(toString());
        error.printStackTrace(taskListener.error("Provision Error"));
    }

    public synchronized void setProvisionErrorNoResourcesState() throws IOException {
        if (state != ProvisioningErrorNoResources) {
            setState(ProvisioningErrorNoResources);
            save();
            fireOnStateChange();

            taskListener.getLogger().println("Provision Error No Resources");
            taskListener.getLogger().println(toString());
        }
    }

    public synchronized void setStartingState() throws IOException {
        setState(Starting);
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Starting");
        taskListener.getLogger().println(toString());
    }

    public synchronized void setStartingErrorState(Throwable error) throws IOException {
        setState(StartingError);
        this.error = error;
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Starting Error");
        taskListener.getLogger().println(toString());
        error.printStackTrace(taskListener.error("Starting Error"));
    }

    public synchronized void setStartedState() throws IOException {
        // Potentially may go from the starting state to the approved state
        // if the master communicates with MetaNectar before the periodic timer executes
        // to process the completion of the start task
        if (this.state == Starting) {
            setState(Started);
            save();
            fireOnStateChange();
        }

        taskListener.getLogger().println("Started");
        taskListener.getLogger().println(toString());
    }

    public synchronized void setApprovedState(RSAPublicKey pk, URL endpoint) throws IOException {
        setState(Approved);
        this.identity = pk.getEncoded();
        this.localEndpoint = endpoint;
        this.approved = true;
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Approved");
        taskListener.getLogger().println(toString());
    }

    public synchronized void setReapprovedState() throws IOException {
        if (state == State.Approved)
            return;

        if (identity == null || localEndpoint == null || approved == false)
            throw new IllegalStateException();

        setState(Approved);
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Approved");
        taskListener.getLogger().println(toString());
    }

    public synchronized void setApprovalErrorState(Throwable error) throws IOException {
        setState(ApprovalError);
        this.error = error;
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Approval Error");
        taskListener.getLogger().println(toString());
        error.printStackTrace(taskListener.error("Approval Error"));
    }

    public synchronized void setConnectedState(Channel channel) throws IOException {
        if (!setChannel(channel))
            return;

        this.error = null;
        fireOnConnected();

        slaveManager = new MetaNectarSlaveManager();
        channel.setProperty(SlaveManager.class.getName(),
                channel.export(SlaveManager.class, slaveManager));

        taskListener.getLogger().println("Connected");
        taskListener.getLogger().println(toString());
    }

    public synchronized void setStoppingState() throws IOException {
        setState(Stopping);
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Stopping");
        taskListener.getLogger().println(toString());
    }

    public synchronized void setStoppingErrorState(Throwable error) throws IOException {
        setState(StoppingError);
        this.error = error;
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Stopping Error");
        taskListener.getLogger().println(toString());
        error.printStackTrace(taskListener.error("Stopping Error"));
    }

    public synchronized void setStoppedState() throws IOException {
        setState(Stopped);
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Stopped");
        taskListener.getLogger().println(toString());
    }

    public synchronized void setTerminateStartedState() throws IOException {
        if (isOnline()) {
            this.channel.close();
        }

        setState(Terminating);
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Terminating");
        taskListener.getLogger().println(toString());
    }

    public synchronized void setTerminateErrorState(Throwable error) throws IOException {
        setState(TerminatingError);
        this.error = error;
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Terminating Error");
        taskListener.getLogger().println(toString());
        error.printStackTrace(taskListener.error("Terminating Error"));
    }

    public synchronized void setTerminateCompletedState(URL snapshot) throws IOException {
        setState(Terminated);
        this.grantId = null;
        this.approved = false;
        this.nodeName = null;
        this.node = null;
        this.nodeId = 0;
        this.localHome = null;
        this.localEndpoint = null;
        this.globalEndpoint = null;
        this.identity = null;
        this.snapshot = snapshot;
        save();
        fireOnStateChange();

        taskListener.getLogger().println("Terminated");
        taskListener.getLogger().println(toString());
    }

    private void setState(State state) {
        this.state = state;
        this.error = null;
        this.timeStamp = new Date().getTime();
    }

    // Event firing

    private final void fireOnStateChange() {
        fire (new FireLambda() {
            public void f(MasterServerListener msl) {
                msl.onStateChange(MasterServer.this);
            }
        });
    }

    private final void fireOnConnected() {
        fire (new FireLambda() {
            public void f(MasterServerListener msl) {
                msl.onConnected(MasterServer.this);
            }
        });
    }

    private final void fireOnDisconnected() {
        fire (new FireLambda() {
            public void f(MasterServerListener msl) {
                msl.onDisconnected(MasterServer.this);
            }
        });
    }

    private interface FireLambda {
        void f(MasterServerListener msl);
    }

    private void fire(FireLambda l) {
        for (MasterServerListener msl : MasterServerListener.all()) {
            try {
                l.f(msl);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Exception when firing event", e);
            }
        }
    }


    // State querying

    public boolean isApprovable() {
        switch (state) {
            case Starting:
            case Started:
            case Approved:
                return true;
            default:
                return false;
        }
    }

    public boolean isProvisioned() {
        return state.ordinal() >= Provisioned.ordinal() && state.ordinal() < Terminated.ordinal();
    }

    public boolean isTerminating() {
        return state.ordinal() > Stopped.ordinal();
    }

    /**
     * Query the master state using a synchronized block.
     *
     * @param f the function to query the master state.
     */
    public synchronized void query(Function<MasterServer, Void> f) {
        f.apply(this);
    }

    // Actions

    public Set<Action> getActionSet() {
        return ImmutableSet.copyOf(Action.values());
    }

    public ImmutableSet<Action> getValidActionSet() {
        return getState().actions;
    }

    public boolean canDoAction(Action a) {
        return state.canDo(a);
    }

    public boolean canProvisionAction() {
        return canDoAction(Action.Provision);
    }

    public boolean canStartAction() {
        return canDoAction(Action.Start);
    }

    public boolean canStopAction() {
        return canDoAction(Action.Stop);
    }

    public boolean canTerminateAction() {
        return canDoAction(Action.Terminate);
    }

    public boolean canDeleteAction() {
        return canDoAction(Action.Delete);
    }

    private void preConditionAction(Action a) throws IllegalStateException {
        if (!canDoAction(a)) {
            throw new IllegalStateException(String.format("Action \"%s\" cannot be performed when in state \"\"", a.name(), getState().name()));
        }
    }

    public synchronized void provisionAndStartAction() throws IOException, IllegalStateException  {
        preConditionAction(Action.Provision);

        Map<String, Object> properties = new HashMap<String, Object>();
        MetaNectar.getInstance().masterProvisioner.provisionAndStart(this, MetaNectar.getInstance().getMetaNectarPortUrl(), properties);
    }

    public synchronized void stopAndTerminateAction() throws IllegalStateException {
        preConditionAction(Action.Stop);

        MetaNectar.getInstance().masterProvisioner.stopAndTerminate(this);
    }

    public synchronized void provisionAction() throws IOException, IllegalStateException  {
        preConditionAction(Action.Provision);

        Map<String, Object> properties = new HashMap<String, Object>();
        MetaNectar.getInstance().masterProvisioner.provision(this, MetaNectar.getInstance().getMetaNectarPortUrl(), properties);
    }

    public synchronized void startAction() throws IllegalStateException {
        preConditionAction(Action.Start);

        MetaNectar.getInstance().masterProvisioner.start(this);
    }

    public synchronized void stopAction() throws IllegalStateException {
        preConditionAction(Action.Stop);

        MetaNectar.getInstance().masterProvisioner.stop(this);
    }

    public synchronized void terminateAction(boolean clean) throws IllegalStateException {
        preConditionAction(Action.Terminate);

        MetaNectar.getInstance().masterProvisioner.terminate(this);
    }

    @Override
    public synchronized void delete() throws IOException, InterruptedException {
        if (state == MasterServer.State.TerminatingError) {
            // TODO disable this, or only enable for development purposes.
            setTerminateCompletedState(null);
        }
        super.delete();
    }

    // Methods for accessing state

    public State getState() {
        return state;
    }

    public int getId() {
        return id;
    }

    public String getEncodedName() {
        return encodedName;
    }

    public String getIdName() {
        return idName;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public Throwable getError() {
        return error;
    }

    public String getGrantId() {
        return grantId;
    }

    public URL getEndpoint() {
        return (globalEndpoint != null) ? globalEndpoint : localEndpoint;
    }

    public String getLocalHome() {
        return localHome;
    }

    public URL getLocalEndpoint() {
        return localEndpoint;
    }

    public URL getGlobalEndpoint() {
        return globalEndpoint;
    }

    public URL getSnapshot() {
        return snapshot;
    }

    public Node getNode() {
        if (node == null) {
            if (nodeName == null)
                return null;

            node = (nodeName.isEmpty()) ? MetaNectar.getInstance() : MetaNectar.getInstance().getNode(nodeName);
        }

        return node;
    }

    public int getNodeId() {
        return nodeId;
    }

    public synchronized RSAPublicKey getIdentity() {
        try {
            if (identity == null)
                return null;

            return (RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(identity));
        } catch (GeneralSecurityException e) {
            LOGGER.log(Level.WARNING, "Failed to load the key", identity);
            identity = null;
            return null;
        }
    }

    public boolean isApproved() {
        return approved;
    }

    public TaskListener getTaskListener() {
        return taskListener;
    }

    public Channel getChannel() {
        return channel;
    }

    public boolean isOnline() {
        return getChannel() != null;
    }

    public boolean isOffline() {
        return getChannel() == null;
    }

    public String getIcon() {
        if (!isApproved())
            return "computer-x.png";

        if(isOffline())
            return "computer-x.png";
        else
            return "computer.png";
    }

    public StatusIcon getIconColor() {
        String icon = getIcon();
        if (isOffline())  {
            return new StockStatusIcon(icon, Messages._JenkinsServer_Status_Offline());
        } else {
            return new StockStatusIcon(icon, Messages._JenkinsServer_Status_Online());
        }
    }


    // Channel methods

    private boolean setChannel(Channel channel) throws IOException, IllegalStateException {
        if (this.channel != null) {
            // TODO we need to check if the existing channel is still alive or not,
            // if not use the new channel, otherwise close the channel

            channel.close();
            LOGGER.warning("Already connected");
            return false;
        }

        this.channel = channel;

        this.channel.addListener(new Channel.Listener() {
            @Override
            public void onClosed(Channel c, IOException cause) {
                MasterServer.this.channel = null;
                MasterServer.this.slaveManager = null;

                // Orderly shutdown will have null exception
                try {
                    if (cause != null) {
                        MasterServer.this.setDisconnectStateCallback(cause);
                    } else {
                        MasterServer.this.setDisconnectStateCallback();
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        return true;
    }

    private void setDisconnectStateCallback() throws IOException {
        fireOnDisconnected();

        taskListener.getLogger().println("Disconnected");
        taskListener.getLogger().println(toString());
    }

    private void setDisconnectStateCallback(Throwable error) throws IOException {
        // Ignore the error if already disconnected due to state change
        if (state.ordinal() > Approved.ordinal()) {
            setDisconnectStateCallback();
        } else {
            this.error = error;

            fireOnDisconnected();

            taskListener.getLogger().println("Disconnected Error");
            taskListener.getLogger().println(toString());
            error.printStackTrace(taskListener.error("Disconnected Error"));
        }
    }


    // UI actions

    public HttpResponse doProvisionAction() throws Exception {
        return new DoActionLambda() {
            public void f() throws Exception {
                provisionAction();
            }
        }.doAction();
    }

    public HttpResponse doStartAction() throws Exception {
        return new DoActionLambda() {
            public void f() {
                startAction();
            }
        }.doAction();
    }

    public HttpResponse doStopAction() throws Exception {
        return new DoActionLambda() {
            public void f() {
                stopAction();
            }
        }.doAction();
    }

    public HttpResponse doTerminateAction() throws Exception {
        return new DoActionLambda() {
            public void f() {
                terminateAction(false);
            }
        }.doAction();
    }

    private abstract class DoActionLambda {
        abstract void f() throws Exception;

        HttpResponse doAction() throws Exception {
            requirePOST();

            f();

            return HttpResponses.redirectToDot();
        }
    }


    // Configuration

    public synchronized void doConfigSubmit(StaplerRequest req,
            StaplerResponse rsp) throws IOException, ServletException, Descriptor.FormException {
        checkPermission(CONFIGURE);

        description = req.getParameter("description");
        save();

        rsp.sendRedirect(".");
    }

    // Test stuff

    public HttpResponse doDisconnect() throws Exception {
        requirePOST();

        this.channel.close();

        taskListener.getLogger().println("Disconnecting");
        taskListener.getLogger().println(toString());
        return HttpResponses.redirectToDot();
    }


    public Map<String,String> getThreadDump() throws IOException, InterruptedException {
        return RemotingDiagnostics.getThreadDump(getChannel());
    }


    //

    /**
     * Returns {@code true} if the page elements should be refreshed by AJAX.
     * @return {@code true} if the page elements should be refreshed by AJAX.
     */
    public boolean isAjaxPageRefresh() {
        return true; //TODO make decision
    }

    /**
     * Returns the number of seconds before the next AJAX refresh.
     * @return the number of seconds before the next AJAX refresh.
     */
    public int getPageRefreshDelay() {
        return isAjaxPageRefresh() ? 1 : 0;
    }

    public String getStatePage() {
        return state.name().toLowerCase();
    }

    /**
     * Gets the view properties configured for this view.
     * @since 1.406
     */
    public DescribableList<MasterServerProperty<?>,MasterServerPropertyDescriptor> getProperties() {
        return properties;
    }

    public List<hudson.model.Action> getPropertyActions() {
        ArrayList<hudson.model.Action> result = new ArrayList<hudson.model.Action>();
        for (MasterServerProperty<?> prop: properties) {
            result.addAll(prop.getMasterServerActions(this));
        }
        return result;
    }

    private Object readResolve() {
        if (properties == null) {
            properties = new PropertyList(this);
        } else {
            properties.setOwner(this);
        }
        return this;
    }

    @Extension
    public static class DescriptorImpl extends TopLevelItemDescriptor {
        @Override
        public String getDisplayName() {
            return "Master";
        }

        @Override
        public TopLevelItem newInstance(ItemGroup parent, String name) {
            return new MasterServer(parent, name);
        }
    }

    /**
     * Create the encoded name.
     */
    public static String createEncodedName(String name) {
        return Util.rawEncode(name);
    }

    /**
     * Create the ID name given a unique ID of a master and it's name.
     */
    public static String createIdName(int id, String name) {
        return Integer.toString(id) + "-" + name;
    }

    public static class PropertyList extends DescribableList<MasterServerProperty<?>,MasterServerPropertyDescriptor> {
        private PropertyList(MasterServer owner) {
            super(owner);
        }

        public PropertyList() {// needed for XStream deserialization
        }

        public MasterServer getOwner() {
            return (MasterServer)owner;
        }

        @Override
        protected void onModified() throws IOException {
            for (MasterServerProperty p : this)
                p.setOwner(getOwner());
        }
    }
    private static final Logger LOGGER = Logger.getLogger(MasterServer.class.getName());
}
