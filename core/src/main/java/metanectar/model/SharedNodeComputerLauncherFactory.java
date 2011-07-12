package metanectar.model;

import com.cloudbees.commons.metanectar.provisioning.ComputerLauncherFactory;
import com.cloudbees.commons.metanectar.provisioning.LeaseId;
import com.thoughtworks.xstream.XStreamException;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.util.IOException2;
import metanectar.provisioning.NotSecretXStream;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.List;

/**
* Created by IntelliJ IDEA.
* User: stephenc
* Date: 12/07/2011
* Time: 13:51
* To change this template use File | Settings | File Templates.
*/
public class SharedNodeComputerLauncherFactory extends ComputerLauncherFactory {

    private String remoteFS;
    private int numExecutors;
    private Node.Mode mode;
    private String labelString;
    private RetentionStrategy<? extends Computer> retentionStrategy;
    private List<? extends NodeProperty<?>> nodeProperties;
    private transient ComputerLauncher launcher;
    private Class<? extends ComputerLauncher> launcherClass;
    private String name;
    private String description;

    public SharedNodeComputerLauncherFactory(LeaseId leaseId, String name, String description, int numExecutors,
                                             String labelString,
                                             String remoteFS, Node.Mode mode,
                                             RetentionStrategy<? extends Computer> retentionStrategy,
                                             List<? extends NodeProperty<?>> nodeProperties,
                                             ComputerLauncher launcher) {
        super(leaseId);
        this.name = name;
        this.description = description;
        this.numExecutors = numExecutors;
        this.labelString = labelString;
        this.remoteFS = remoteFS;
        this.mode = mode;
        this.retentionStrategy = retentionStrategy;
        this.nodeProperties = nodeProperties;
        this.launcherClass = launcher.getClass();
        this.launcher = launcher; // save init on this JVM
    }

    /**
     * Constructor for the de-serialization path
     */
    SharedNodeComputerLauncherFactory() {
    }

    private void writeObject(ObjectOutputStream stream) throws IOException {
        // fill in the latest data for the launcher
        stream.defaultWriteObject();
        try {
            stream.writeUTF(NotSecretXStream.INSTANCE.toXML(launcher));
        } catch (XStreamException e) {
            throw new IOException2(e);
        }
    }

    private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
        stream.defaultReadObject();
        try {
            launcher = launcherClass.cast(NotSecretXStream.INSTANCE.fromXML(stream.readUTF()));
        } catch (XStreamException e) {
            throw new IOException2(e);
        }
    }

    @Override
    public String getNodeDescription() {
        return description;
    }

    @Override
    public Node.Mode getMode() {
        return mode;
    }

    @Override
    public RetentionStrategy getRetentionStrategy() {
        return retentionStrategy;
    }

    @Override
    public List<? extends NodeProperty<?>> getNodeProperties() {
        return nodeProperties == null ? Collections.<NodeProperty<?>>emptyList() : nodeProperties;
    }

    @Override
    public String getNodeName() {
        return name;
    }

    @Override
    public String getRemoteFS() {
        return remoteFS;
    }

    @Override
    public int getNumExecutors() {
        return numExecutors;
    }

    @Override
    public String getLabelString() {
        return labelString;
    }

    @Override
    public synchronized ComputerLauncher getOrCreateLauncher() throws IOException, InterruptedException {
        return launcher;
    }
}
