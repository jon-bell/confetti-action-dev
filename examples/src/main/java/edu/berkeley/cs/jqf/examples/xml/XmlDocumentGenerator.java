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
package edu.berkeley.cs.jqf.examples.xml;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.generator.Size;
import com.pholser.junit.quickcheck.internal.GeometricDistribution;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.examples.common.AlphaStringGenerator;
import edu.berkeley.cs.jqf.examples.common.Dictionary;
import edu.berkeley.cs.jqf.examples.common.DictionaryBackedStringGenerator;
import edu.berkeley.cs.jqf.fuzz.central.KnarrWorker;
import edu.columbia.cs.psl.phosphor.PreMain;
import edu.columbia.cs.psl.phosphor.TaintUtils;
import edu.columbia.cs.psl.phosphor.runtime.MultiTainter;
import edu.columbia.cs.psl.phosphor.runtime.Taint;
import edu.gmu.swe.knarr.runtime.ExpressionTaint;
import edu.gmu.swe.knarr.runtime.Symbolicator;
import org.junit.Assume;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Text;
import za.ac.sun.cs.green.expr.Expression;

/**
 * A generator for XML documents.
 *
 * @author Rohan Padhye
 */
public class XmlDocumentGenerator extends Generator<Document> {

    private static DocumentBuilderFactory documentBuilderFactory =
            DocumentBuilderFactory.newInstance();

    private static GeometricDistribution geometricDistribution =
            new GeometricDistribution(){ // Copied from pholser.junit.quickcheck.internal.GeometricDistribution
                                        //With taint propogation manually added
                double probabilityOfMean(double mean) {
                    if (mean <= 0)
                        throw new IllegalArgumentException("Need a positive mean, got " + mean);

                    return 1 / mean;
                }
                public int sampleWithMean(double mean, SourceOfRandomness random) {
                    return this.sample(this.probabilityOfMean(mean), random);
                }

                int sample(double p, SourceOfRandomness random) {
                    this.ensureProbability(p);
                    if (p == 1.0D) {
                        return 0;
                    } else {
                        double uniform = random.nextDouble();
                        if(PreMain.RUNTIME_INST) {
                            Taint tag = MultiTainter.getTaint(uniform);
                            //TODO check if this is doing anything, i don't think it is but don't want to delete it right now
                            int ret = (int) Math.ceil(Math.log(1.0D - uniform) / Math.log(1.0D - p));
                            ret = Symbolicator.symbolic(ret, tag);
                            Expression exp = (Expression) tag.getSingleLabel();
                            Exception loc = new Exception();
                            loc.fillInStackTrace();
                            exp.metadata = loc;

                            return ret;
                        }else{
                            return (int) Math.ceil(Math.log(1.0D - uniform) / Math.log(1.0D - p));
                        }
                    }
                }
                private void ensureProbability(double p) {
                    if (p <= 0 || p > 1)
                        throw new IllegalArgumentException("Need a probability in (0, 1], got " + p);
                }
            };

    /** Mean number of child nodes for each XML element. */
    private static final double MEAN_NUM_CHILDREN = 4;

    /** Mean number of attributes for each XML element. */
    private static final double MEAN_NUM_ATTRIBUTES = 2;

    /**
     * Minimum size of XML tree.
     * @see {@link #configure(Size)}
     */
    private int minDepth = 0;

    /**
     * Maximum size of XML tree.
     * @see {@link #configure(Size)}
     */
    private int maxDepth = 4;

    private Generator<String> stringGenerator = new AlphaStringGenerator();

    public XmlDocumentGenerator() {
        super(Document.class);
    }

    /**
     * Configures the minimum/maximum size of the XML document.
     *
     * This method is not usually invoked directly. Instead, use
     * the `@Size` annotation on fuzzed parameters to configure
     * automatically.
     *
     * @param size the min/max size of the XML document
     */
    public void configure(Size size) {
        minDepth = size.min();
        maxDepth = size.max();
    }


    /**
     * Configures the string generator used by this generator to use
     * a dictionary. This is useful for overriding the default
     * arbitrary string generator with something that pulls tag names
     * from a predefined list.
     *
     * @param dict the dictionary file
     * @throws IOException if the dictionary file cannot be read
     */
    public void configure(Dictionary dict) throws IOException {
        stringGenerator = new DictionaryBackedStringGenerator(dict.value(), stringGenerator);
    }

    /**
     * Generators a random XML document.
     * @param random a source of pseudo-random values
     * @param status generation state
     * @return a randomly-generated XML document
     */
    @Override
    public Document generate(SourceOfRandomness random, GenerationStatus status) {
        DocumentBuilder builder;
        try {
            builder = documentBuilderFactory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }

        if (stringGenerator == null) {
            stringGenerator = gen().type(String.class);
        }

        Document document = builder.newDocument();
        try {
            populateDocument(document, random, status);
        } catch (DOMException e) {
            Assume.assumeNoException(e);
        }
        return document;

    }

    private String makeString(SourceOfRandomness random, GenerationStatus status) {
        return stringGenerator.generate(random, status);
    }

    private Document populateDocument(Document document, SourceOfRandomness random, GenerationStatus status) {
        Element root = document.createElement(makeString(random, status));
        populateElement(document, root, random, status, 0);
        document.appendChild(root);
        // Useful for debugging - will show all taint flows from sources to output string
        // WARNING - when you have this on, you'll also get the constraints applied from the printer!
        //if(PreMain.RUNTIME_INST || System.getenv("DEBUG") != null) //Only print debug in knarr
        //    System.out.println(XMLDocumentUtils.documentToString(document));
        return document;
    }

    private void populateElement(Document document, Element elem, SourceOfRandomness random, GenerationStatus status, int depth) {
        // Add attributes
        int numAttributes = Math.max(0, geometricDistribution.sampleWithMean(MEAN_NUM_ATTRIBUTES, random)-1);
        for (int i = 0; i < numAttributes; i++) {
            elem.setAttribute(makeString(random, status), makeString(random, status));
        }
        // Make children
        if (depth < minDepth || (depth < maxDepth && random.nextBoolean())) {
            int numChildren = Math.max(0, geometricDistribution.sampleWithMean(MEAN_NUM_CHILDREN, random)-1);
            for (int i = 0; i < numChildren; i++) {
                Element child = document.createElement(makeString(random, status));
                populateElement(document, child, random, status, depth+1);
                elem.appendChild(child);
            }
        } else if (random.nextBoolean()) {
            // Add text
            Text text = document.createTextNode(makeString(random, status));
            elem.appendChild(text);
        } else if (random.nextBoolean()) {
            // Add text as CDATA
            Text text = document.createCDATASection(makeString(random, status));
            elem.appendChild(text);
        }
    }
}
