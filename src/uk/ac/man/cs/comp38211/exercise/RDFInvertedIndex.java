/** 
 *
 * Copyright (c) University of Manchester - All Rights Reserved
 * Unauthorised copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Kristian Epps <kristian@xepps.com>, August 28, 2013
 * 
 * RDF Inverted Index
 * 
 * This Map Reduce program should read in a set of RDF/XML documents and output
 * the data in the form:
 * 
 * {predicate, object]}, [subject1, subject2, ...] 
 * 
 * @author Kristian Epps
 * 
 */
package uk.ac.man.cs.comp38211.exercise;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.StringTokenizer;


import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.rdf.model.Property;

import uk.ac.man.cs.comp38211.io.array.ArrayListWritable;
import uk.ac.man.cs.comp38211.io.pair.PairOfStrings;
import uk.ac.man.cs.comp38211.ir.StopAnalyser;
import uk.ac.man.cs.comp38211.util.XParser;

public class RDFInvertedIndex extends Configured implements Tool
{
    private static final Logger LOG = Logger
            .getLogger(RDFInvertedIndex.class);

    public static class Map extends
            Mapper<LongWritable, Text, PairOfStrings, Text>
    {

        protected Text document = new Text();

        protected PairOfStrings predobj = new PairOfStrings();
        protected PairOfStrings subjpred = new PairOfStrings();
        protected PairOfStrings subjobj = new PairOfStrings();

        // declare subj
        protected Text subj = new Text();
        //declare pred
        protected Text pred = new Text();
        //declare obj
        protected Text obj = new Text();
        
        // The StopAnalyser class helps remove stop words
        @SuppressWarnings("unused")
        private StopAnalyser stopAnalyser = new StopAnalyser();
        
        // addIndexEntry provides each entry of the index, consisting of the RDF triples and some metadata for the object.
        private void addIndexEntry(Context context, Resource subject, Property predicate, RDFNode object, String RDFNodeType, String caseFlag)
            throws IOException, InterruptedException
        {
          switch (RDFNodeType)
          {
            case "RDFResource":
              predobj.set("predobj: " + predicate.toString(), "resource:" + object.toString());
              // Output subject+object pair terms for object as RDF Resource.
              subjobj.set("subjobj: " + subject.toString(), "resource:" + object.toString());
              
              // Output if the resource is a object as RDF Resource.
              obj.set("resource:" + object.toString());  
              break;

            case "PlainLiteral":
              String metadata = "PlainLiteral:" + caseFlag + "::";              
              // Output predicate+object pair terms for object as plain literal.
              predobj.set("predobj: " + predicate.toString(), metadata + object.toString()); 
              // Output subject+object pair terms for object as plain literal.
              subjobj.set("subjobj: " + subject.toString(), metadata + object.toString());
          
              // Output if the resource is a object as plain literal.
              obj.set(metadata + object.toString());
              break;

            case "TypedLiteral":
              // Output predicate+object pair terms for object as typed literal.
              predobj.set("predobj: " + predicate.toString(), "TypedLiteral:" + object.toString());
              // Output subject+object pair terms for object as typed literal.
              subjobj.set("subjobj: " + subject.toString(), "TypedLiteral:" + object.toString());          
              
              // Output if the resource is a object as typed literal.
              obj.set("TypedLiteral:" + object.toString());
              break;

            default:
              return;        
          }
          
          context.write(predobj, subj);
          context.write(subjobj, pred);
          context.write(subjpred, obj);
        } // addIndexEntry
        private Boolean isValidAndOptimised(String token) {
            return token.length() > 0 && token != null && !token.isEmpty() && !StopAnalyser.isStopWord(token.toLowerCase()) ;
          }
        
        // removes all non-alphanumeric characters from the start and end of the word
        private String symbolRemoval(String input) {
          return input.replaceAll("[^a-zA-Z0-9]*$|^[^a-zA-Z0-9]*", "");

        }

        public void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException
        {
            // This statement ensures we read a full rdf/xml document in
            // before we try to do anything else
            if(!value.toString().contains("</rdf:RDF>"))
            {
                document.set(document.toString() + value.toString());
                return;
            }

            // We have to convert the text to a UTF-8 string. This must be
            // enforced or errors will be thrown.
            String contents = document.toString() + value.toString();
            contents = new String(contents.getBytes(), "UTF-8");

            // The string must be cast to an inputstream for use with jena
            InputStream fullDocument = IOUtils.toInputStream(contents);
            document = new Text();

            // Create a model
            Model model = ModelFactory.createDefaultModel();

            try
            {
                model.read(fullDocument, null);

                StmtIterator iter = model.listStatements();

                // Iterate over all the triples, set and output them
                while(iter.hasNext())
                {
                    Statement stmt      = iter.nextStatement();
                    Resource  subject   = stmt.getSubject();
                    Property  predicate = stmt.getPredicate();
                    RDFNode   object    = stmt.getObject();
                    
                  //set subj, pred and subjpred
                    subj.set(subject.toString());
                    pred.set(predicate.toString());
                    subjpred.set("subjpred " + subject.toString(), predicate.toString());


                    if(object instanceof Literal) {
                    	
                        Literal literal = object.asLiteral();

                        if (literal.getDatatype() == null) {
                        	
                            //get lexical form of the text
                        	String text = literal.getLexicalForm();
                        	//get language of the text
                            String language = literal.getLanguage();
                            
                            // divide text into tokens
                            StringTokenizer itr = new StringTokenizer(text);
                            // while it has more tokens left
                            while (itr.hasMoreTokens())
                            { 
                            	String token = itr.nextToken();
                            	// remove symbols
                     		    token = symbolRemoval(token);
                     		    
                     		    // if token is not empty and is not a stop word
                                if (isValidAndOptimised(token)) {

                                //boolean variable to decide case sensitivity
                                boolean caseSensitive = Character.isUpperCase(token.toCharArray()[0]);
                                
                                //default case case insensitive ,
                                String flag = "i";
                                // if the token is upper case the set sensitivity flag to "s"
                                if(caseSensitive){ 
                                    flag = "s";
                                }// if case sensitive

                                Literal typedLiteral = model.createLiteral(token, language);
                                // define literal type and add case
                                String  litType = "PlainLiteral:" + flag + ":";

                                //set subjobj, predobj and obj
                                subjobj.set("subjobj " + subject.toString(), litType  + typedLiteral.toString());
                                predobj.set("predobj " + predicate.toString(), litType + typedLiteral.toString());
                                obj.set(litType + typedLiteral.toString());

                                // predicate + object -> subject
                                context.write(predobj, subj);
                                // subject + object ->  predicate            
                                context.write(subjobj, pred);
                                // subject + predicate  -> object                      
                                context.write(subjpred, obj);
                                }//if 
                            }//while
                        }// if newEntry.getDatatype() == null
                        
                        //else for typed literal
                        else {
                            //set subjobj, predobj and obj
                        	subjobj.set("subjobj " + subject.toString(), "TypedLiteral:" + literal.toString());
                            predobj.set("predobj " + predicate.toString(), "TypedLiteral:" + literal.toString());
                            obj.set("TypedLiteral:" + literal.toString());

                            // predicate + object -> subject
                            context.write(predobj, subj);
                            // subject + object ->  predicate            
                            context.write(subjobj, pred);
                            // subject + predicate  -> object                      
                            context.write(subjpred, obj);
                        }//else
                    }// if object instanceof Literal
                    
                    // else for resource
                    else{
                        //set subjobj, predobj and obj
                        predobj.set("predobj " + predicate.toString(), "Resource:" + object.toString());
                        subjobj.set("subjobj " + subject.toString(), "Resource:" + object.toString());
                        obj.set("Resource:" + object.toString());

                        // predicate + object -> subject
                        context.write(predobj, subj);
                        // subject + object ->  predicate            
                        context.write(subjobj, pred);
                        // subject + predicate  -> object                      
                        context.write(subjpred, obj);
                    }//else
                }//while
            }//try
            catch(Exception e)
            {
                LOG.error(e);
            }//catch
        }//map
    }//Map

    public static class Reduce extends Reducer<PairOfStrings, Text, PairOfStrings, ArrayListWritable<Text>>
    {

        // This reducer turns an iterable into an ArrayListWritable, sorts it and outputs it
        public void reduce(
                PairOfStrings key,
                Iterable<Text> values,
                Context context) throws IOException, InterruptedException
        {
            ArrayListWritable<Text> postings = new ArrayListWritable<Text>();

            Iterator<Text> iter = values.iterator();

            while(iter.hasNext()) {
            	Text copy = new Text(iter.next());
                postings.add(copy);
            }

            Collections.sort(postings);
            context.write(key, postings);

        }//reduce
    }//Reduce

    public RDFInvertedIndex()
    {
    }

    private static final String INPUT = "input";
    private static final String OUTPUT = "output";
    private static final String NUM_REDUCERS = "numReducers";

    @SuppressWarnings({ "static-access" })
    public int run(String[] args) throws Exception
    {
        Options options = new Options();

        options.addOption(OptionBuilder.withArgName("path").hasArg()
                .withDescription("input path").create(INPUT));
        options.addOption(OptionBuilder.withArgName("path").hasArg()
                .withDescription("output path").create(OUTPUT));
        options.addOption(OptionBuilder.withArgName("num").hasArg()
                .withDescription("number of reducers").create(NUM_REDUCERS));

        CommandLine cmdline = null;
        CommandLineParser parser = new XParser(true);

        try
        {
            cmdline = parser.parse(options, args);
        }
        catch (ParseException exp)
        {
            System.err.println("Error parsing command line: "
                    + exp.getMessage());
            System.err.println(cmdline);
            return -1;
        }

        if (!cmdline.hasOption(INPUT) || !cmdline.hasOption(OUTPUT))
        {
            System.out.println("args: " + Arrays.toString(args));
            HelpFormatter formatter = new HelpFormatter();
            formatter.setWidth(120);
            formatter.printHelp(this.getClass().getName(), options);
            ToolRunner.printGenericCommandUsage(System.out);
            return -1;
        }

        String inputPath = cmdline.getOptionValue(INPUT);
        String outputPath = cmdline.getOptionValue(OUTPUT);

        Job RDFIndex = new Job(new Configuration());

        RDFIndex.setJobName("Inverted Index 1");
        RDFIndex.setJarByClass(RDFInvertedIndex.class);
        RDFIndex.setMapperClass(Map.class);
        RDFIndex.setReducerClass(Reduce.class);
        RDFIndex.setMapOutputKeyClass(PairOfStrings.class);
        RDFIndex.setMapOutputValueClass(Text.class);
        RDFIndex.setOutputKeyClass(PairOfStrings.class);
        RDFIndex.setOutputValueClass(ArrayListWritable.class);
        FileInputFormat.setInputPaths(RDFIndex, new Path(inputPath));
        FileOutputFormat.setOutputPath(RDFIndex, new Path(outputPath));

        long startTime = System.currentTimeMillis();

        RDFIndex.waitForCompletion(true);
        if(RDFIndex.isSuccessful())
            LOG.info("Job successful!");
        else
            LOG.info("Job failed.");

        LOG.info("Job Finished in " + (System.currentTimeMillis() - startTime)
                / 1000.0 + " seconds");

        return 0;
    }

    public static void main(String[] args) throws Exception
    {
        ToolRunner.run(new RDFInvertedIndex(), args);
    }
}