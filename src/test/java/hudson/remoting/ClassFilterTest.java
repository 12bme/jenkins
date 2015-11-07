package hudson.remoting;

import hudson.remoting.Channel.Mode;
import hudson.remoting.CommandTransport.CommandReceiver;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tests the effect of {@link ClassFilter}.
 *
 * <p>
 * This test code targets each of the known layers where object serialization is used.
 * Specifically, those are {@link ObjectInputStream} (and subtypes) created in:
 *
 * <ul>
 * <li>{@link Capability#read(InputStream)}
 * <li>{@link UserRequest#deserialize(Channel, byte[], ClassLoader)},
 * <li>{@link ChannelBuilder#makeTransport(InputStream, OutputStream, Mode, Capability)}
 * <li>{@link AbstractByteArrayCommandTransport#setup(Channel, CommandReceiver)}
 * <li>{@link AbstractSynchronousByteArrayCommandTransport#read()}
 * </ul>
 *
 * @author Kohsuke Kawaguchi
 */
public class ClassFilterTest implements Serializable {

    /**
     * North can defend itself from south but not the other way around.
     */
    private transient InProcessRunner runner = new InProcessRunner() {
        @Override
        protected ChannelBuilder configureNorth() {
            return super.configureNorth()
                    .withClassFilter(new TestFilter());
        }
    };

    private transient Channel north, south;

    private static class TestFilter extends ClassFilter {
        @Override
        protected boolean isBlacklisted(String name) {
            if (name.contains("Security218"))
                return true;
            return false;
        }
    }

    /**
     * Set up a channel pair where french side is well protected by british side is not.
     */
    @Before
    public void setUp() throws Exception {
        north = runner.start();
        south = runner.south;
        ATTACKS.clear();
    }

    @After
    public void tearDown() throws Exception {
        runner.stop(north);
    }

    /**
     * Makes sure {@link Capability#read(InputStream)} rejects unexpected payload.
     */
    @Test
    public void capabilityRead() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(Mode.TEXT.wrap(baos));
        oos.writeObject(new Security218("rifle"));
        oos.close();

        try {
            Capability.read(new ByteArrayInputStream(baos.toByteArray()));
        } catch (SecurityException e) {
            assertEquals("Rejected: "+Security218.class.getName(), e.getMessage());
        }
    }

    @Test
    public void userRequest() throws Exception {
        try {
            final Security218 a = new Security218("napoleon");
            south.call(new CallableBase<Void, IOException>() {
                @Override
                public Void call() throws IOException {
                    a.toString();   // this will ensure 'a' gets sent over
                    return null;
                }
            });
            fail("Expected call to fail");
        } catch (IOException e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);

            assertTrue(sw.toString(), sw.toString().contains("Rejected: "+Security218.class.getName()));
            assertTrue(ATTACKS.toString(), ATTACKS.isEmpty());
            assertFalse(ATTACKS.contains("napoleon>north"));
        }
    }

    /**
     * Control case for {@link #userRequest()} that proves that we are testing the right thing.
     */
    @Test
    public void userRequest_control() throws Exception {
        final Security218 a = new Security218("caesar");
        north.call(new CallableBase<Void, IOException>() {
            @Override
            public Void call() throws IOException {
                a.toString();   // this will ensure 'a' gets sent over
                return null;
            }
        });
        assertTrue(ATTACKS.contains("caesar>south"));
    }

    /**
     * An attack payload that leaves a trace on the receiver side if it gets read from the stream.
     */
    static class Security218 implements Serializable {
        private final String attack;

        public Security218(String attack) {
            this.attack = attack;
        }

        private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
            ois.defaultReadObject();
            ATTACKS.add(attack+">"+Channel.current().getName());
        }
    }

    /**
     * Successful attacks will leave a trace here.
     */
    static Set<String> ATTACKS = new HashSet<String>();
}