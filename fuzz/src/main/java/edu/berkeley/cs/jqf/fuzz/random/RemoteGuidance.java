package edu.berkeley.cs.jqf.fuzz.random;

import edu.berkeley.cs.jqf.fuzz.guidance.Guidance;
import edu.berkeley.cs.jqf.fuzz.guidance.Result;
import edu.berkeley.cs.jqf.fuzz.junit.GuidedFuzzing;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.LinkedList;
import java.util.Random;
import java.util.function.Consumer;

public class RemoteGuidance implements Guidance {

    private ObjectInputStream ois;
    private ObjectOutputStream oos;
    private LinkedList<byte[]> originalInput;
    private int n;
    private LinkedList<byte[]> input;
    private LinkedList<byte[]> output = new LinkedList<>();
    private Random rnd = new Random();
    private boolean inputReady = false;
    private boolean firstSuccessResult = false;

    public RemoteGuidance() throws IOException {
        Socket s = new Socket(InetAddress.getLocalHost(), 54321);
        ois = new ObjectInputStream(s.getInputStream());
        oos = new ObjectOutputStream(s.getOutputStream());
    }

    @Override
    public boolean hasInput() {
        try {
            if (!inputReady) {
                originalInput = (LinkedList) ois.readObject();
                n = ois.readInt();
                inputReady = true;
            }
            output.clear();
            input = new LinkedList<>(originalInput);

            return true;
        } catch (ClassNotFoundException e) {
            throw new Error(e);
        } catch (IOException e) {
            return false;
        }
    }

    public InputStream getInput() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new Error("Not implemented");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0)
                    return 0;

                if (input.isEmpty())
                    return random(b, off, len);

                byte[] next = input.removeFirst();

                // 0 size byte array means "use random here"
                if (next.length == 0)
                    return random(b, off, len);

                if (next.length != len) {
                    input.clear();
                    return random(b, off, len);
                }

                System.arraycopy(next, 0, b, off, len);
                output.addLast(next);
                return len;
            }

            private int random(byte[] b, int off, int len) {
                if (len == 0)
                    return 0;

                byte[] bs = new byte[len];
                rnd.nextBytes(bs);
                System.arraycopy(bs, 0, b, off, len);
                output.addLast(bs);
                return len;
            }
        };
    }

    /**
     * Handles the result of a fuzz run.
     *
     * @param result   the result of the fuzzing trial
     * @param error    the error thrown during the trial, or <tt>null</tt>
     */
    @Override
    public void handleResult(Result result, Throwable error) {
        try {

            if (n-- == 0) {
                // Reached end of try window, send result regardless
                oos.writeObject(new LinkedList<>(output));
                oos.writeObject(result);
                inputReady = false;
                return;
            }

            if (result == Result.SUCCESS && !firstSuccessResult) {
                // Found first successful result
                firstSuccessResult = true;
                oos.writeObject(new LinkedList<>(output));
                oos.writeObject(result);
                inputReady = false;
                return;
            }

            if (result == Result.INVALID || result == Result.SUCCESS) {
                // Keep trying until we get a valid result
                return;
            }

            if (result == Result.FAILURE || result == Result.TIMEOUT) {
                if (error != null)
                    error.printStackTrace();

                oos.writeObject(new LinkedList<>(output));
                oos.writeObject(result);
                inputReady = false;
                return;
            }

//            System.out.println();
//            for (byte[] a : output) {
//                ByteBuffer bf = ByteBuffer.wrap(a);
//                switch (a.length) {
//                    case 4:
//                        System.out.println("\t" + bf.getInt());
//                        break;
//                    case 2:
//                        System.out.println("\t" + bf.getShort());
//                        break;
//                    case 1:
//                        System.out.println("\t" + Boolean.toString(a[0] == 0));
//                        break;
//                    default:
//                        System.out.println("\t" + new String(a));
//                }
//            }

            throw new Error("Dead code");
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    @Override
    public Consumer<TraceEvent> generateCallBack(Thread thread) {
        return new Consumer<TraceEvent>() {
            @Override
            public void accept(TraceEvent traceEvent) {
                // Do nothing
            }
        };
    }

    public static void main(String[] args) {
        if (args.length < 2){
            System.err.println("Usage: java " + RemoteGuidance.class + " TEST_CLASS TEST_METHOD [MAX_TRIALS]");
            System.exit(1);
        }

        String testClassName  = args[0];
        String testMethodName = args[1];
        Long maxTrials = args.length > 2 ? Long.parseLong(args[2]) : Long.MAX_VALUE;

        try {
            // Load the guidance
            RemoteGuidance guidance = new RemoteGuidance();

            // Run the Junit test
            GuidedFuzzing.run(testClassName, testMethodName, guidance, System.out);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }

    }

    public void setArgs(Object[] args) {
    }
}