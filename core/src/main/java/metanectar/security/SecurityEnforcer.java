package metanectar.security;

import com.cloudbees.commons.metanectar.context.ItemNodeContext;
import com.cloudbees.commons.metanectar.context.NodeContext;
import com.cloudbees.commons.metanectar.context.NodeContextContributor;
import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import metanectar.model.ConnectedMaster;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Controls how to enforce the security setting on masters.
 *
 * <p>
 * The admin picks one out of all the available implementations.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class SecurityEnforcer extends AbstractDescribableImpl<SecurityEnforcer> implements ExtensionPoint {

    /**
     * Called whenever {@link ItemNodeContext} is pushed to {@link ConnectedMaster}.
     * This is where the enforcement logic is implemented.
     */
    protected abstract void updateNodeContext(ConnectedMaster node, ItemNodeContext context);

    public SecurityEnforcerDescriptor getDescriptor() {
        return (SecurityEnforcerDescriptor)super.getDescriptor();
    }

    /**
     * Returns the global default {@link SecurityEnforcer} setting.
     */
    public static SecurityEnforcer getCurrent() {
        return Hudson.getInstance().getDescriptorByType(GlobalSetting.class).getGlobal();
    }


    /**
     * Invokes {@link SecurityEnforcer} when we push {@link NodeContext} to masters.
     */
    @Extension
    public static class NodeContextContributorImpl extends NodeContextContributor<ConnectedMaster> {
        @Override
        public void update(ConnectedMaster node, NodeContext context) {
            getCurrent().updateNodeContext(node, (ItemNodeContext)context);
        }

        @Override
        public void contribute(ConnectedMaster node, NodeContext context) {
            update(node,context);
        }
    }

    /**
     * Exposes the config UI to the system config page.
     *
     * TODO: re-implement this using the new GlobalConfiguration extension point
     */
    @Extension
    public static class GlobalSetting extends Descriptor<GlobalSetting> implements Describable<GlobalSetting> {
        private volatile SecurityEnforcer global;

        public GlobalSetting() {
            super(GlobalSetting.class);
            load();
            if (global==null)
                global = new NoSecurityEnforcer();
        }

        /**
         * Returns the global default {@link SecurityEnforcer} setting.
         */
        public SecurityEnforcer getGlobal() {
            return global;
        }

        public void setGlobal(SecurityEnforcer global) {
            this.global = global;
            save();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            setGlobal(req.bindJSON(SecurityEnforcer.class,json.getJSONObject("global")));
            return true;
        }

        public Descriptor<GlobalSetting> getDescriptor() {
            return this;
        }

        @Override
        public String getDisplayName() {
            return "";
        }
    }
}
