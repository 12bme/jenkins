package metanectar.provisioning;

import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.DumbSlave;
import hudson.slaves.NodeProvisioner;
import metanectar.test.MetaNectarTestCase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Emulate a simple cloud that provisions dumb slaves.
 *
 * @author Paul Sandoz
 */
class TestSlaveCloud extends Cloud {
    private final transient MetaNectarTestCase mtc;

    private final int delay;

    public TestSlaveCloud(MetaNectarTestCase mtc, int delay) {
        super("test-cloud");
        this.mtc = mtc;
        this.delay = delay;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        List<NodeProvisioner.PlannedNode> r = new ArrayList<NodeProvisioner.PlannedNode>();

        for( ; excessWorkload>0; excessWorkload-- ) {
            r.add(new NodeProvisioner.PlannedNode(name+"-slave#"+excessWorkload,
                    Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                        public Node call() throws Exception {
                            Thread.sleep(delay);

                            System.out.println("launching slave");

                            DumbSlave slave = mtc.createSlave();

                            Computer computer = slave.toComputer();
                            computer.connect(false).get();
                            synchronized (this) {
                                System.out.println(computer.getName()+" launch"+(computer.isOnline()?"ed successfully":" failed"));
                                System.out.println(computer.getLog());
                            }
                            return slave;
                        }
                    })
                    ,1));
        }
        return r;
    }

    @Override
    public boolean canProvision(Label label) {
        return false;
    }

    public Descriptor<Cloud> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
