package hudson.license;

import com.trilead.ssh2.crypto.Base64;
import hudson.Util;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * @author Kohsuke Kawaguchi
 */
public class ServerResponse {
    public final String licenseKey, cert;
    /**
     * If the registration fails, this field represents a plain text message.
     */
    public final String message;

    private License license;

    @DataBoundConstructor
    public ServerResponse(String licenseKey, String cert, String message) throws IOException {
        this.licenseKey = licenseKey;
        this.cert = cert;
        this.message = Util.fixEmpty(new String(Base64.decode(message.toCharArray()),"UTF-8"));
    }

    /**
     * Accepts this response and persists the license.
     */
    public void save() throws IOException, ServletException {
        LicenseManager lm = LicenseManager.getInstance();
        lm.setLicense(licenseKey,cert);
        license = lm.getParsed();
    }

    /**
     * Once the license is persisted, this method returns the parsed license object.
     */
    public License getLicense() {
        return license;
    }

    public boolean isValid() {
        return message==null;
    }
}
