package edu.berkeley.cs.jqf.fuzz.central;

import edu.berkeley.cs.jqf.fuzz.guidance.Result;
import edu.berkeley.cs.jqf.fuzz.util.Coverage;

import java.io.IOException;
import java.util.LinkedList;

public class ZestClient extends Central {

    public ZestClient() throws IOException {
        super();
        oos.writeObject(Type.Zest);
        oos.flush();
    }

    /* Client:
     * 1. Send input
     * 2. Send coverage
     * 3. Select input
     * 3. Receive instructions
     */
    public void sendInput(LinkedList<byte[]> inputRequests, Result result, int id) throws IOException {
        oos.writeObject(inputRequests);
        oos.writeObject(result);
        oos.writeInt(id);
        oos.flush();
    }

    public void selectInput(int id) throws IOException {
        oos.writeObject(new Integer(id));
    }

    public LinkedList<int[]> receiveInstructions() throws IOException {
        try {
            return (LinkedList<int[]>) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new Error(e);
        }
    }

    public void sendCoverage(Coverage totalCoverage) {
        // TODO
    }
}