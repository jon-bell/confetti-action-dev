package edu.berkeley.cs.jqf.fuzz.central;

import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.Sort;
import com.microsoft.z3.Z3Exception;
import edu.gmu.swe.knarr.runtime.Coverage;
import edu.gmu.swe.knarr.server.ConstraintOptionGenerator;
import edu.gmu.swe.knarr.server.HashMapStateStore;
import edu.gmu.swe.knarr.server.StateStore;
import za.ac.sun.cs.green.Green;
import za.ac.sun.cs.green.Instance;
import za.ac.sun.cs.green.expr.Expression;
import za.ac.sun.cs.green.expr.Operation;
import za.ac.sun.cs.green.expr.StringConstant;
import za.ac.sun.cs.green.expr.Variable;
import za.ac.sun.cs.green.expr.VisitorException;
import za.ac.sun.cs.green.service.canonizer.ModelCanonizerService;
import za.ac.sun.cs.green.service.factorizer.ModelFactorizerService;
import za.ac.sun.cs.green.service.z3.ModelZ3JavaService;
import za.ac.sun.cs.green.service.z3.Z3JavaTranslator;
import za.ac.sun.cs.green.util.Configuration;
import za.ac.sun.cs.green.util.NotSatException;

import java.io.*;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;

public class Z3Worker extends Worker {
    private LinkedList<LinkedList<Expression>> constraints = new LinkedList<>();
    private Data data;

    private static final File Z3_OUTPUT_DIR;

    static {
        String z3Dir = System.getProperty("Z3_OUTPUT_DIR");
        if (z3Dir != null) {
            File f = new File(z3Dir);
            if (!f.exists())
                f.mkdirs();

            if (!f.isDirectory())
                throw new Error("Path " + f + " is not a directory");

            Z3_OUTPUT_DIR = f;
        } else {
            Z3_OUTPUT_DIR = null;
        }
    }

    public Z3Worker() {
        super(null, null);
        data = new Data();
        data.green = new Green();
        Properties props = new Properties();
        props.setProperty("green.services", "model");
        props.setProperty("green.service.model", "(slice (canonize z3))");
        props.setProperty("green.service.model.slice", "za.ac.sun.cs.green.service.slicer.SATSlicerService");
        props.setProperty("green.service.model.canonize", "za.ac.sun.cs.green.service.canonizer.ModelCanonizerService");
        props.setProperty("green.service.model.z3", "za.ac.sun.cs.green.service.z3.ModelZ3JavaService");
        // props.setProperty("green.store",
        // "za.ac.sun.cs.green.store.redis.RedisStore");
        Configuration config = new Configuration(data.green, props);
        config.configure();
        data.slicer = new ModelFactorizerService(data.green);
        data.canonizer = new ModelCanonizerService(data.green);
        data.variableMap = new HashMap<Variable, Variable>();
        data.modeler = new ModelZ3JavaService(data.green, null);
        data.stateStore = new HashMapStateStore();
        data.optionGenerator = new ConstraintOptionGenerator();
    }

    @Override
    protected void work() throws IOException, ClassNotFoundException {
        // Set timeout
        {
            String to;
            if ((to = System.getProperty("Z3_TIMEOUT")) != null)
                Z3JavaTranslator.timeoutMS = Integer.parseInt(to);
            else
                Z3JavaTranslator.timeoutMS = 3600 * 1000; // 1h
        }

        int solved = -1;
        while (true) {
            solved++;
            LinkedList<Expression> csToSolve;

            synchronized (constraints) {
                if (constraints.isEmpty()) {
                    try {
                        constraints.wait();
                    } catch (InterruptedException _) {
                    }
                    continue;
                }

                csToSolve = constraints.removeFirst();
            }

            Map<String, Expression> res = new HashMap<>();
            for (Expression e : csToSolve)
                res.put("c" + res.size(), e);
            ArrayList<AbstractMap.SimpleEntry<String, Object>> sat = new ArrayList<>();
            HashSet<String> unsat = new HashSet<>();

            sat.clear();
            unsat.clear();

            if (Z3_OUTPUT_DIR != null)
                dumpToTXTFile(Paths.get(Z3_OUTPUT_DIR.getAbsolutePath(), "constraints" + solved + ".txt").toFile(), res);

            try {
                solve(res, sat, unsat);

                for (String s : unsat)
                    System.out.println(res.get(s));

                if (unsat.isEmpty()){
                    // Try negating constraints of branches
                    res.clear();
                    for (Expression cs : csToSolve) {
                        sat.clear();
                        unsat.clear();
                        if (cs.metadata instanceof Coverage.BranchData ) {
                            Coverage.BranchData data = (Coverage.BranchData) cs.metadata;
                            res.put("c" + res.size(), new Operation(Operation.Operator.NOT, cs));
                            solve(res, sat, unsat);

                            if (Z3_OUTPUT_DIR != null)
                                dumpToTXTFile(Paths.get(Z3_OUTPUT_DIR.getAbsolutePath(), "constraints" + solved + ".txt").toFile(), res);


                            if (!unsat.isEmpty()) {
                                // Unsat, try different things to make it SAT
                                res.remove("c" + (res.size() - 1));

                                // Is it UNSAT because of a String.equals?
                                // Maybe it's because the lengths don't match perfectly
                                // Turn that into startsWith
                                String equalsHint = replaceEqualsByStartsWith(res, cs);
                                if (equalsHint != null) {
                                    // Give hint to JQF
                                    System.out.println("Equals hint: " + equalsHint);
                                } else {
                                    // Failed, stop trying
                                    for (String s : unsat)
                                        System.out.println(res.get(s));
                                }
                            }

                            res.remove("c" + (res.size() - 1));
                            res.put("c" + res.size(), cs);
                        } else {
                            res.put("c" + res.size(), cs);
                        }
                    }
                }
            } catch (Z3Exception | ClassCastException e) {
                System.err.println(e.getMessage());
                e.printStackTrace();
            }


            continue;
        }
    }

    private String replaceEqualsByStartsWith(Map<String, Expression> res, Expression cs) {
        // Check if the constraint is EQUALS

        if (!(cs instanceof Operation && ((Operation)cs).getOperand(1) instanceof Operation))
            return null;

        Operation outer = (Operation) cs;
        Operation inner = (Operation) outer.getOperand(1);

        if (inner.getOperator() != Operation.Operator.EQUALS)
            return null;

        // The constraint is EQUALS
        // Replace it by STARTSWITH and try again

       Operation newInner = new Operation(Operation.Operator.STARTSWITH, inner.getOperand(0), inner.getOperand(1));
       Operation newOuter = new Operation(outer.getOperator(), outer.getOperand(0), newInner);

       Expression argumentToEquals = inner.getOperand(1);

        ArrayList<AbstractMap.SimpleEntry<String, Object>> sat = new ArrayList<>();
        HashSet<String> unsat = new HashSet<>();

        // Our new negated constraint
        res.put("c" + res.size(), new Operation(Operation.Operator.NOT, newOuter));

        String hint = null;
        if (argumentToEquals instanceof StringConstant) {
            // We know what's the argument to equals
            hint = ((StringConstant)argumentToEquals).getValue();
        } else {
            // TODO we need to ask Z3 to give us what is the argument to equals
            // TODO the code below was a try but it doesn't quite work, it always gets UNSAT
            // TODO probably some silly reason
//            // A string variable that is large enough for us to read what we just compared against
//            int n = 50;
//            Expression auxStringVar = new StringVariable("aux");
//            for (int i = 0 ; i < 50 ; i++) {
//                auxStringVar = new Operation(Operation.Operator.CONCAT, auxStringVar, new BVVariable("aux"+i, 32));
//            }
//
//            res.put("aux1", new Operation(Operation.Operator.STARTSWITH, auxStringVar, argumentToEquals));
        }

        solve(res, sat, unsat);

        if (!unsat.isEmpty()) {
            // No good, didn't work
            res.remove("c" + (res.size()-1));
            return null;
        } else {
            // It worked, get the string from the solution
            res.remove("c" + (res.size()-1));
            return hint;
        }
    }

    private void solve(Map<String, Expression> constraints, ArrayList<AbstractMap.SimpleEntry<String, Object>> sat, HashSet<String> unsat) {

        // Initialize Z3 instance
        Instance in = new Instance(data.green, null, constraints);
        for (Z3JavaTranslator.Z3GreenBridge e : data.optionGenerator.generateOptions(data.modeler.getUnderlyingExpr(in)))
            data.stateStore.addOption(e);

        Z3JavaTranslator.Z3GreenBridge newExp = data.stateStore.getNewOption();
        boolean issat = false;
        final String prefix = "autoVar_";
        while (newExp != null && !issat) {
            issat = true;
//					System.out.println("Trying out new version: " + newExp);
            try{
                @SuppressWarnings("unchecked")
                long start = System.currentTimeMillis();


                ModelZ3JavaService.Solution sol = data.modeler.solve(newExp);

                if (sol.sat) {
//					System.out.println("SAT");
//								System.out.println("SAT: " + sol);
                    for(String v : sol.data.keySet())
                    {
                        sat.add(new AbstractMap.SimpleEntry<>(v, sol.data.get(v)));
                    }
                } else {
//					System.out.println("NOT SAT");
                    for (String k : sol.data.keySet()) {
                        unsat.add(k);
                    }
                    issat = false;
                }
            }
            catch(NotSatException ex)
            {
                issat = false;
                System.out.println("Not sat");
            }
            newExp = data.stateStore.getNewOption();
        }

        Collections.sort(sat, new Comparator<AbstractMap.SimpleEntry<String, Object>>() {
            @Override
            public int compare(AbstractMap.SimpleEntry<String, Object> o1, AbstractMap.SimpleEntry<String, Object> o2) {
                if (o1.getKey().startsWith(prefix) && o2.getKey().startsWith(prefix)) {
                    Integer i1 = Integer.valueOf(o1.getKey().substring(prefix.length()));
                    Integer i2 = Integer.valueOf(o2.getKey().substring(prefix.length()));
                    return i1.compareTo(i2);
                }

                return o1.getKey().compareTo(o2.getKey());
            }
        });
    }

    private void dumpToTXTFile(File file, Map<String, Expression> constraints) throws IOException {
        try (PrintStream ps = new PrintStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            ps.println("(set-option :produce-unsat-cores true)");

            Context ctx = new Context();
            {
                // Get all variables into a new translator
                Z3JavaTranslator translator = new Z3JavaTranslator(ctx);

                for (Expression e : constraints.values())
                    try {
                        e.accept(translator);
                    } catch (VisitorException e1) {
                        throw new Error(e1);
                    }

                // Declare functions
               // for (String function : translator.getFunctions().keySet())
                 //   ps.println("(declare-fun " + function + " (Int (_ BitVec 32)) (_ BitVec 32))");

                // Declare variables
                HashSet<String> seen = new HashSet<>();
                for (Expr v : translator.getVariableMap().values()) {
                    if (seen.add(v.toString())) {
                        Sort s = v.getSort();
                        ps.println("(declare-const " + v + " " + s + ")");
                    }
                }
            }

            // Print constraints
            for (Map.Entry<String, Expression> entry : constraints.entrySet()) {
                Z3JavaTranslator t = new Z3JavaTranslator(ctx);
                try {
                    entry.getValue().accept(t);
                } catch (VisitorException e1) {
                    throw new Error(e1);
                }

                // Print constraint number as a comment
                ps.println("; c" + entry.getKey());

                // Print Knarr constraint as comment
                ps.println("; " + entry.getValue().toString());

                ps.println("(assert (!" + t.getTranslation() + " :named " + entry.getKey() + "))");
                ps.println();
            }

            // Check model
            ps.println("(check-sat)");
            ps.println("(get-model)");

            ps.println("; uncomment below to get unsat core");
            ps.println(";(get-unsat-core)");
        }
    }

    public void addConstraints(LinkedList<Expression> cs) {
        synchronized (this.constraints) {
            this.constraints.addLast(cs);
            this.constraints.notifyAll();
        }
    }

    public void addConstraints(String filename) {
        // Deserialization
        try
        {
            File file = new File(filename);
            // Reading the object from a file
            FileInputStream fis = new FileInputStream(file);
            ObjectInputStream in = new ObjectInputStream(fis);

            LinkedList<Expression> constraints = (LinkedList<Expression>)in.readObject();

            in.close();
            fis.close();

            synchronized (this.constraints) {
                this.constraints.addLast(constraints);
                this.constraints.notifyAll();
            }

        }

        catch(IOException ex)
        {
            System.out.println("Could not de-serialize constraints");
        }

        catch(ClassNotFoundException ex)
        {
            System.out.println("ClassNotFoundException is caught");
        }
    }

    private static class Data {
        Green green;
        ModelFactorizerService slicer;
        ModelCanonizerService canonizer;
        ModelZ3JavaService modeler;
        Map<Variable, Variable> variableMap;
        StateStore stateStore;
        ConstraintOptionGenerator optionGenerator;
    }
}
