/*
 * Copyright (c) 2017-2018 The Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package edu.berkeley.cs.jqf.examples.common;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.central.Coordinator;
import edu.berkeley.cs.jqf.fuzz.guidance.StringEqualsHintingInputStream;
import edu.berkeley.cs.jqf.fuzz.knarr.KnarrGuidance;
import edu.columbia.cs.psl.phosphor.runtime.Taint;
import edu.columbia.cs.psl.phosphor.struct.LazyCharArrayObjTags;
import edu.columbia.cs.psl.phosphor.struct.TaintedObjectWithObjTag;
import edu.gmu.swe.knarr.runtime.ExpressionTaint;
import edu.gmu.swe.knarr.runtime.Symbolicator;
import za.ac.sun.cs.green.expr.Expression;
import za.ac.sun.cs.green.expr.FunctionCall;
import za.ac.sun.cs.green.expr.IntConstant;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class DictionaryBackedStringGenerator extends Generator<String> {

    private final List<String> dictionary;
    private Generator<String> fallback;

    public static Boolean useHints = false;

    public DictionaryBackedStringGenerator(String source, Generator<String> fallback) throws IOException {
        super(String.class);
        this.dictionary = new ArrayList<>();
        this.fallback = fallback;

        // Read dictionary words
        try (InputStream in = ClassLoader.getSystemClassLoader().getResourceAsStream(source)) {
            if (in == null) {
                throw new FileNotFoundException("Dictionary file not found: " + source);
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String item;
            while ((item = br.readLine()) != null) {
                dictionary.add(item);
            }
        }
    }

    @Override
    public String generate(SourceOfRandomness random, GenerationStatus status) {
        int choice = random.nextInt(0, Integer.MAX_VALUE);

        Coordinator.StringHint[] hints = StringEqualsHintingInputStream.getHintsForCurrentInput();

        String word = "";
        if (hints != null && hints.length > 0) {
            word = hints[0].getHint();
            if (hints[0].getType() == Coordinator.HintType.INDEXOF) {
                String origWord = dictionary.get(choice % dictionary.size());
                if (origWord.length() <= 1) {
                    word = origWord + word;
                } else {
                    int middle = origWord.length() / 2;
                    word = origWord.substring(0, middle) + word + origWord.substring(middle);
                }
            } else if (hints[0].getType() == Coordinator.HintType.ENDSWITH) {
                String origWord = dictionary.get(choice % dictionary.size());
                word = origWord + word;
            } else if (hints[0].getType() == Coordinator.HintType.STARTSWITH) {
                String origWord = dictionary.get(choice % dictionary.size());
                word = word + origWord;
            } else if (hints[0].getType() == Coordinator.HintType.ISEMPTY) {
                String origWord = dictionary.get(choice % dictionary.size());
                if (word.isEmpty()) {
                    word = "a";
                } else {
                    word = "";
                }
            }
            //System.out.println("Hint word: " + word);
            if (hints[0].getType() == Coordinator.HintType.Z3) {
                StringEqualsHintingInputStream.z3HintsUsedInCurrentInput = true;
            }
            StringEqualsHintingInputStream.hintUsedInCurrentInput = true;
        } else {
            choice = choice % dictionary.size();
            word = dictionary.get(choice);
        }

        return applyTaints(word, choice);
    }

    private static int currentFunctionNumber = 0;

    private static String applyTaints(String result, Object taint) {
        if (result == null || result.length() == 0 || !(taint instanceof TaintedObjectWithObjTag))
            return result;


        // New string to avoid adding taints to the dictionary itself
        String ret = new String(result);

        Expression t = (Expression) ((Taint)((TaintedObjectWithObjTag)taint).getPHOSPHOR_TAG()).getSingleLabel();

        if (Symbolicator.getTaints(result) instanceof LazyCharArrayObjTags) {
            LazyCharArrayObjTags taints = (LazyCharArrayObjTags) Symbolicator.getTaints(result);
            taints.taints = new Taint[result.length()];
            for (int i = 0 ; i< taints.taints.length ; i++) {
                taints.taints[i] = new ExpressionTaint(new FunctionCall(
                        "gen" + currentFunctionNumber,
                        new Expression[]{ new IntConstant(i), t}));
            }
            KnarrGuidance.generatedStrings.put("gen"+currentFunctionNumber, result);
            currentFunctionNumber += 1;
        }

        // New string so that Phosphor can compute the tag for the string itself based on the tag for each character
        ret = new String(ret.getBytes(), 0, ret.length());

        return ret;
    }

}
