package metanectar.provisioning;

import metanectar.model.MasterServer;
import metanectar.model.MasterServerListener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author Paul Sandoz
 */
public abstract class LatchMasterServerListener extends MasterServerListener {
    protected final CountDownLatch cdl;

    public LatchMasterServerListener(int i) {
        MasterServerListener.all().add(0, this);
        cdl = new CountDownLatch(i);
    }

    public void await(long timeout, TimeUnit unit) throws InterruptedException {
        cdl.await(timeout, unit);
    }

    public void countDown() {
        cdl.countDown();
    }

    public long getCount() {
        return cdl.getCount();
    }

    public static class ProvisionListener extends LatchMasterServerListener {
        public ProvisionListener(int i) {
            super(i);
        }

        public void onProvisioning(MasterServer ms) {
            countDown();
        }

        public void onProvisioned(MasterServer ms) {
            countDown();
        }
    }

    public static class TerminateListener extends LatchMasterServerListener {
        public TerminateListener(int i) {
            super(i);
        }

        public void onTerminating(MasterServer ms) {
            countDown();
        }

        public void onTerminated(MasterServer ms) {
            countDown();
        }
    }
}
