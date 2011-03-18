package metanectar.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Represents a TCP/IP-like connection with the other side.
 *
 * <p>
 * The basis of this is an {@link InputStream}/{@link OutputStream} pair,
 * plus {@link AgentStatusListener} to report the events in this connection.
 *
 * <p>
 * Because the early hand-shaking phase of the protocol depends on
 * {@link DataInputStream}/{@link DataOutputStream}, that is provided
 * here for convenience.
 *
 * @author Kohsuke Kawaguchi, Paul Sandoz
 */
public class Connection {
    public final InputStream in;
    public final OutputStream out;

    public final DataInputStream din;
    public final DataOutputStream dout;

    private AgentStatusListener listener;

    public Connection(Socket socket) throws IOException {
        this(socket.getInputStream(),socket.getOutputStream());
    }

    public Connection(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
        this.din = new DataInputStream(in);
        this.dout = new DataOutputStream(out);
    }

    public AgentStatusListener getListener() {
        return listener;
    }

    public void setListener(AgentStatusListener listener) {
        this.listener = listener;
    }

//
//
// Convenience methods
//
//
    public void writeUTF(String msg) throws IOException {
        dout.writeUTF(msg);
    }

    public String readUTF() throws IOException {
        return din.readUTF();
    }

    /**
     * Sends a serializable object.
     */
    public void sendObject(Object o) throws IOException {
        ObjectOutputStream oos = new ObjectOutputStream(out);
        oos.writeObject(o);
        // don't close oss, which will close the underlying stream
        // no need to flush either, given the way oos is implemented
    }

    /**
     * Receives an object sent by {@link #sendObject(Object)}
     */
    public <T> T readObject() throws IOException, ClassNotFoundException {
        ObjectInputStream ois = new ObjectInputStream(in);
        return (T)ois.readObject();
    }
}
