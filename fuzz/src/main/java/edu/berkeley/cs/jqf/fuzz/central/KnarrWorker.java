package edu.berkeley.cs.jqf.fuzz.central;

import edu.gmu.swe.knarr.internal.ConstraintDeserializer;
import edu.gmu.swe.knarr.runtime.StringUtils;
import za.ac.sun.cs.green.expr.*;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.*;

public class KnarrWorker extends Worker {
    private ArrayList<LinkedList<byte[]>> inputs = new ArrayList<>();
    private ArrayList<Integer> fuzzing = new ArrayList<>();
    private Coordinator c;

    public KnarrWorker(ObjectInputStream ois, ObjectOutputStream oos, Coordinator c) {
        super(ois, oos);
        this.c = c;
    }

    ConstraintDeserializer deserializer = new ConstraintDeserializer();
    byte[] buffer = new byte[1024*10];
    public LinkedList<Expression> getConstraints(Coordinator.Input input) throws IOException {
        // Send input to Knarr process
        oos.writeObject(input.bytes);
        oos.writeObject(input.hints);
        oos.writeObject(input.instructions);
        oos.writeObject(input.targetedHints);
        oos.writeInt(input.id);
        oos.writeBoolean(input.isValid);
        oos.reset();
        oos.flush();

        // Get constraints from Knarr process
        LinkedList<Expression> constraints;
        int nBytes = ois.readInt();
        if(nBytes > buffer.length){
            buffer = new byte[nBytes];
        }
        int offset = 0;
        while(offset < nBytes){
            int read = ois.read(buffer, offset, nBytes - offset);
            if(read == -1){
                if(offset == 0){
                    throw new IOException("Read -1 bytes");
                }
                break;
            }
            offset += read;
        }
        long start = System.currentTimeMillis();
        System.out.println("Read " + nBytes + " of constraints");
        constraints = deserializer.fromBytes(buffer, 0, nBytes);
        long end =System.currentTimeMillis();
        System.out.println("Deserialized in " + (end - start));

        return constraints;
    }
    public HashMap<String, String> getGeneratedStrings() throws IOException {
        int nEntries = ois.readInt();
        HashMap<String, String> ret = new HashMap<>();
        for(int i = 0; i < nEntries; i++){
            ret.put(ois.readUTF(), ois.readUTF());
        }
        return ret;
    }

    static long constraintsProcessed;

    public static void findControllingBytes(Expression e, HashSet<Integer> bytes, HashSet<Coordinator.StringHint> stringEqualsArgs, Coordinator.Input input, Coordinator.Branch controlledBranch) {
        constraintsProcessed++;
        if (e instanceof Variable) {
            Variable v = (Variable) e;
            if (v.getName().startsWith("autoVar_")) {
                bytes.add(Integer.parseInt(v.getName().substring("autoVar_".length())));
            }
        } else if (e instanceof Operation) {
            Operation op = (Operation) e;
            if(controlledBranch != null) {
                if (e.metadata != null && op.getOperator() == Operation.Operator.EQ || op.getOperator() == Operation.Operator.NE) {
                    //is this a char comparison?
                    Z3Worker.StringEqualsVisitor leftOfEQ = new Z3Worker.StringEqualsVisitor(op.getOperand(0));
                    Z3Worker.StringEqualsVisitor rightOfEQ = new Z3Worker.StringEqualsVisitor(op.getOperand(1));
                    try {
                        op.getOperand(0).accept(leftOfEQ);
                        op.getOperand(1).accept(rightOfEQ);
                    } catch (VisitorException visitorException) {
                        //visitorException.printStackTrace();
                    }

                    Z3Worker.StringEqualsVisitor symbolicString = null;
                    int comparedChar = 0;
                    if (leftOfEQ.hasSymbolicVariable() && leftOfEQ.isSimpleGeneratorFunctionExpression() && op.getOperand(1) instanceof IntConstant) {
                        symbolicString = leftOfEQ;
                        comparedChar = (int) ((IntConstant) op.getOperand(1)).getValueLong();
                    } else if (rightOfEQ.hasSymbolicVariable() && rightOfEQ.isSimpleGeneratorFunctionExpression() && op.getOperand(0) instanceof IntConstant) {
                        symbolicString = rightOfEQ;
                        comparedChar = (int) ((IntConstant) op.getOperand(0)).getValueLong();
                    }

                    if (op.getOperator() == Operation.Operator.NE && comparedChar == 0) {
                        //skip, this is just some check to make sure it's not a null char, we'll never generate that anyway...
                    } else if (symbolicString != null) {
                        Z3Worker.GeneratedCharacter symbChar = symbolicString.getSymbolicChars().getFirst();
                        //TODO this seems like more noise than utility
                        //input.targetedHints.add(new Coordinator.CharHint(comparedChar, input.generatedStrings.get(symbolicString.getFunctionName()), Coordinator.HintType.EQUALS,
                        //        symbChar.bytePositionInRandomGen, symbChar.numBytesInRandomGen, symbChar.index));
                    }
                }
                if (e.metadata != null && e.metadata instanceof HashSet) {
                    Iterator<StringUtils.StringComparisonRecord> it = ((HashSet<StringUtils.StringComparisonRecord>) e.metadata).iterator();
                    while (it.hasNext()) {
                        boolean ignore = false;
                        StringUtils.StringComparisonRecord cur = it.next();
                        String originalString = null;
                        //Find the right genName for this...
                        Z3Worker.StringEqualsVisitor leftOfEQ = new Z3Worker.StringEqualsVisitor(op.getOperand(0));
                        Z3Worker.StringEqualsVisitor rightOfEQ = new Z3Worker.StringEqualsVisitor(op.getOperand(1));
                        try {
                            op.getOperand(0).accept(leftOfEQ);
                            op.getOperand(1).accept(rightOfEQ);
                        } catch (VisitorException visitorException) {
                            //visitorException.printStackTrace();
                        }
                        Z3Worker.StringEqualsVisitor v = leftOfEQ;
                        if (v.getFunctionName() == null) {
                            v = rightOfEQ;
                        }
                        if (input.generatedStrings != null) {
                            if (v.getFunctionName() != null) {
                                originalString = input.generatedStrings.get(v.getFunctionName());
                            }
                        }
                        //Look and see if it will even be feasible to do this: if we want a startsWith, but the first chars are concrete
                        //or if we want an equals and there are non-symbolic chars, we should bail!
                        if (v.getNumConcreteCharsInSymbolic() > 0) {
                            ignore = true;
                        }
                        if (v.getGeneratorFunctionNames().size() > 1) {
                            ignore = true;
                        }
                        if (!ignore) {
                            //TODO debug when we're looking at the setCode branch
                            if (controlledBranch != null && controlledBranch.source != null && controlledBranch.source.contains("isLineTerminator")) {
                                System.out.println("...");
                            }
                            switch (cur.getComparisionType()) {
                                case EQUALS:
                                    if (originalString != null && !originalString.equals(cur.getStringCompared())) {
                                        //stringEqualsArgs.add(new Coordinator.StringHint(cur.getSecond(), Coordinator.HintType.EQUALS, KnarrGuidance.extractChoices(e))); //TODO disable when not debugging, this is slow
                                        addStringHintIfNew(stringEqualsArgs, new Coordinator.StringHint(cur.getStringCompared(), Coordinator.HintType.EQUALS, controlledBranch));
                                    }
                                    break;
                                case INDEXOF:
                                    //stringEqualsArgs.add(new Coordinator.StringHint(cur.getSecond(), Coordinator.HintType.INDEXOF, KnarrGuidance.extractChoices(e))); //TODO disable when not debugging, this is slow
                                    //stringEqualsArgs.add(new Coordinator.StringHint(cur.getSecond(), Coordinator.HintType.STARTSWITH, KnarrGuidance.extractChoices(e))); //TODO disable when not debugging, this is slow
                                    //stringEqualsArgs.add(new Coordinator.StringHint(cur.getSecond(), Coordinator.HintType.ENDSWITH, KnarrGuidance.extractChoices(e))); //TODO disable when not debugging, this is slow

                                    //TODO the indexOf comparison might have had nothing to do with this comparison, and should be ignored in that case.
                                    //We don't have a way to skip those, though. So, for now, this stays off (otherwise closure's parser brings in tons of INDEXOF /*, // */ hints that are garbage
                                    //String startsWith = cur.getStringCompared();
                                    //if (originalString != null && !originalString.contains(cur.getStringCompared())) {
                                    //    startsWith = cur.getStringCompared() + originalString;
                                    //    //addStringHintIfNew(stringEqualsArgs, new Coordinator.StringHint(cur.getStringCompared(), Coordinator.HintType.INDEXOF, controlledBranch));
                                    //    addStringHintIfNew(stringEqualsArgs, new Coordinator.StringHint(cur.getStringCompared(), startsWith, Coordinator.HintType.STARTSWITH, controlledBranch));
                                    //    //addStringHintIfNew(stringEqualsArgs, new Coordinator.StringHint(cur.getStringCompared(), endsWith, Coordinator.HintType.ENDSWITH, controlledBranch));
                                    //}
                                    break;
                                case STARTSWITH:
                                    if (originalString != null && !originalString.startsWith(cur.getStringCompared())) {
                                        String startsWith = cur.getStringCompared() + originalString;
                                        //stringEqualsArgs.add(new Coordinator.StringHint(cur.getSecond(), Coordinator.HintType.STARTSWITH, KnarrGuidance.extractChoices(e))); //TODO disable when not debugging, this is slow
                                        addStringHintIfNew(stringEqualsArgs, new Coordinator.StringHint(cur.getStringCompared(), startsWith, Coordinator.HintType.STARTSWITH, controlledBranch));
                                    }
                                    break;
                                case ENDWITH:
                                    //if(originalString != null && !originalString.endsWith(cur.getStringCompared())){
                                    //    String endsWith = originalString + cur.getStringCompared();
                                    //    stringEqualsArgs.add(new Coordinator.StringHint(cur.getStringCompared(), endsWith, Coordinator.HintType.ENDSWITH, controlledBranch));
                                    //}
                                    break;
                                case ISEMPTY:
                                    if (originalString != null && !originalString.isEmpty()) {
                                        addStringHintIfNew(stringEqualsArgs, new Coordinator.StringHint("", Coordinator.HintType.ISEMPTY, controlledBranch));
                                    }
                                    break;
                            }

                        }
                    }
                }
            }
            for (int i = 0 ; i < op.getArity() ; i++)
                findControllingBytes(op.getOperand(i), bytes, stringEqualsArgs, input, controlledBranch);
        } else if (e instanceof FunctionCall) {
            FunctionCall f = (FunctionCall) e;
            for (Expression arg : f.getArguments())
                findControllingBytes(arg, bytes, stringEqualsArgs, input, controlledBranch);
        }
    }
    private static void addStringHintIfNew(HashSet<Coordinator.StringHint> hints, Coordinator.StringHint hintToAdd){
        int c = hintToAdd.targetBranch.addSuggestion(hintToAdd);
        hintToAdd.priority = c;
            hints.add(hintToAdd);
    }

    @Override
    protected void work() throws IOException, ClassNotFoundException {
        throw new Error("Shouldn't execute in separate thread");
    }
}
