/*
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.spark.impl.multilayer;

import org.apache.spark.Accumulable;
import org.apache.spark.Accumulator;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.mllib.linalg.Matrix;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.canova.api.records.reader.RecordReader;
import org.deeplearning4j.nn.api.Updater;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.FeedForwardLayer;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.updater.aggregate.UpdaterAggregator;
import org.deeplearning4j.spark.canova.RecordReaderFunction;
import org.deeplearning4j.spark.impl.common.Adder;
import org.deeplearning4j.spark.impl.common.BestScoreAccumulator;
import org.deeplearning4j.spark.impl.common.gradient.GradientAdder;
import org.deeplearning4j.spark.impl.common.misc.GradientFromTupleFunction;
import org.deeplearning4j.spark.impl.common.misc.INDArrayFromTupleFunction;
import org.deeplearning4j.spark.impl.common.misc.UpdaterFromGradientTupleFunction;
import org.deeplearning4j.spark.impl.common.misc.UpdaterFromTupleFunction;
import org.deeplearning4j.spark.impl.common.updater.UpdaterAggregatorCombiner;
import org.deeplearning4j.spark.impl.common.updater.UpdaterElementCombiner;
import org.deeplearning4j.spark.impl.multilayer.gradientaccum.GradientAccumFlatMap;
import org.deeplearning4j.spark.util.MLLibUtil;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import java.io.Serializable;
import java.util.List;

/**
 * Master class for spark
 *
 * @author Adam Gibson
 */
public class SparkDl4jMultiLayer implements Serializable {

    private transient SparkContext sparkContext;
    private transient JavaSparkContext sc;
    private MultiLayerConfiguration conf;
    private MultiLayerNetwork network;
    private Broadcast<INDArray> params;
    private Broadcast<Updater> updater;
    private boolean averageEachIteration = false;
    public final static String AVERAGE_EACH_ITERATION = "org.deeplearning4j.spark.iteration.average";
    public final static String ACCUM_GRADIENT = "org.deeplearning4j.spark.iteration.accumgrad";
    public final static String DIVIDE_ACCUM_GRADIENT = "org.deeplearning4j.spark.iteration.dividegrad";

    private Accumulator<Double> best_score_acc = null;

    private static final Logger log = LoggerFactory.getLogger(SparkDl4jMultiLayer.class);

    /**
     * Instantiate a multi layer spark instance
     * with the given context and network.
     * This is the prediction constructor
     * @param sparkContext  the spark context to use
     * @param network the network to use
     */
    public SparkDl4jMultiLayer(SparkContext sparkContext, MultiLayerNetwork network) {
        this(new JavaSparkContext(sparkContext),network);
    }

    public SparkDl4jMultiLayer(JavaSparkContext javaSparkContext, MultiLayerNetwork network ){
        this.sparkContext = javaSparkContext.sc();
        sc = javaSparkContext;
        this.conf = this.network.getLayerWiseConfigurations().clone();
        this.network = network;
        this.network.init();
        this.averageEachIteration = sparkContext.conf().getBoolean(AVERAGE_EACH_ITERATION,false);
        this.best_score_acc = BestScoreAccumulator.create(sparkContext);
    }

    /**
     * Training constructor. Instantiate with a configuration
     * @param sparkContext the spark context to use
     * @param conf the configuration of the network
     */
    public SparkDl4jMultiLayer(SparkContext sparkContext, MultiLayerConfiguration conf) {
        this.sparkContext = sparkContext;
        sc = new JavaSparkContext(this.sparkContext);
        this.conf = conf.clone();
        this.network = new MultiLayerNetwork(conf);
        this.network.init();
        this.averageEachIteration = sparkContext.conf().getBoolean(AVERAGE_EACH_ITERATION, false);
        this.best_score_acc = BestScoreAccumulator.create(sparkContext);
    }

    /**
     * Training constructor. Instantiate with a configuration
     * @param sc the spark context to use
     * @param conf the configuration of the network
     */
    public SparkDl4jMultiLayer(JavaSparkContext sc, MultiLayerConfiguration conf) {
        this(sc.sc(),conf);
    }

    /**
     * Train a multi layer network based on the path
     * @param path the path to the text file
     * @param labelIndex the label index
     * @param recordReader the record reader to parse results
     * @return {@link MultiLayerNetwork}
     */
    public MultiLayerNetwork fit(String path,int labelIndex,RecordReader recordReader) {
        JavaRDD<String> lines = sc.textFile(path);
        // gotta map this to a Matrix/INDArray
        FeedForwardLayer outputLayer = (FeedForwardLayer) conf.getConf(conf.getConfs().size() - 1).getLayer();
        JavaRDD<DataSet> points = lines.map(new RecordReaderFunction(recordReader
                , labelIndex, outputLayer.getNOut()));
        return fitDataSet(points);

    }

    public MultiLayerNetwork getNetwork() {
        return network;
    }

    public void setNetwork(MultiLayerNetwork network) {
        this.network = network;
    }

    /**
     * Predict the given feature matrix
     * @param features the given feature matrix
     * @return the predictions
     */
    public Matrix predict(Matrix features) {
        return MLLibUtil.toMatrix(network.output(MLLibUtil.toMatrix(features)));
    }


    /**
     * Predict the given vector
     * @param point the vector to predict
     * @return the predicted vector
     */
    public Vector predict(Vector point) {
        return MLLibUtil.toVector(network.output(MLLibUtil.toVector(point)));
    }

    /**
     * Fit the given rdd given the context.
     * This will convert the labeled points
     * to the internal dl4j format and train the model on that
     * @param rdd the rdd to fitDataSet
     * @return the multi layer network that was fitDataSet
     */
    public MultiLayerNetwork fit(JavaRDD<LabeledPoint> rdd,int batchSize) {
        FeedForwardLayer outputLayer = (FeedForwardLayer) conf.getConf(conf.getConfs().size() - 1).getLayer();
        return fitDataSet(MLLibUtil.fromLabeledPoint(rdd, outputLayer.getNOut(), batchSize));
    }


    /**
     * Fit the given rdd given the context.
     * This will convert the labeled points
     * to the internal dl4j format and train the model on that
     * @param sc the org.deeplearning4j.spark context
     * @param rdd the rdd to fitDataSet
     * @return the multi layer network that was fitDataSet
     */
    public MultiLayerNetwork fit(JavaSparkContext sc,JavaRDD<LabeledPoint> rdd) {
        FeedForwardLayer outputLayer = (FeedForwardLayer) conf.getConf(conf.getConfs().size() - 1).getLayer();
        return fitDataSet(MLLibUtil.fromLabeledPoint(sc, rdd, outputLayer.getNOut()));
    }

    /**Fit the data, splitting into smaller data subsets if necessary. This allows large {@code JavaRDD<DataSet>}s)
     * to be trained as a set of smaller steps instead of all together.<br>
     * Using this method, training progresses as follows:<br>
     * train on {@code examplesPerFit} examples -> average parameters -> train on {@code examplesPerFit} -> average
     * parameters etc until entire data set has been processed<br>
     * <em>Note</em>: The actual number of splits for the input data is based on rounding up.<br>
     * Suppose {@code examplesPerFit}=1000, with {@code rdd.count()}=1200. Then, we round up to 2000 examples, and the
     * network will then be fit in two steps (as 2000/1000=2), with 1200/2=600 examples at each step. These 600 examples
     * will then be distributed approximately equally (no guarantees) amongst each executor/core for training.
     *
     * @param rdd Data to train on
     * @param examplesPerFit Number of examples to learn on (between averaging) across all executors. For example, if set to
     *                       1000 and rdd.count() == 10k, then we do 10 sets of learning, each on 1000 examples.
     *                       To use all examples, set maxExamplesPerFit to Integer.MAX_VALUE
     * @return Trained network
     */
    public MultiLayerNetwork fitDataSet(JavaRDD<DataSet> rdd, int examplesPerFit ){
        int nSplits;
        if(examplesPerFit == Integer.MAX_VALUE) nSplits = 1;
        else {
            long nExamples = rdd.count();
            if(nExamples%examplesPerFit==0){
                nSplits = (int)(nExamples / examplesPerFit);
            } else {
                nSplits = (int)(nExamples / examplesPerFit) + 1;
            }
        }

        if(nSplits == 1){
            fitDataSet(rdd);
        } else {
            double[] splitWeights = new double[nSplits];
            for( int i=0; i<nSplits; i++ ) splitWeights[i] = 1.0 / nSplits;
            JavaRDD<DataSet>[] subsets = rdd.randomSplit(splitWeights);
            for( int i=0; i<subsets.length; i++ ){
                log.info("Initiating distributed training of subset {} of {}",(i+1),subsets.length);
                fitDataSet(subsets[i]);
            }
        }
        return network;
    }

    /**
     * Fit the dataset rdd
     * @param rdd the rdd to fitDataSet
     * @return the multi layer network
     */
    public MultiLayerNetwork fitDataSet(JavaRDD<DataSet> rdd) {
        int iterations = conf.getConf(0).getNumIterations();
        log.info("Running distributed training:  (averaging each iteration = " + averageEachIteration + "), (iterations =" +
                iterations + "), (num partions = " + rdd.partitions().size() + ")");
        if(!averageEachIteration) {
            //Do multiple iterations and average once at the end
            runIteration(rdd);
        } else {
            //Temporarily set numIterations = 1. Control numIterations externall here so we can average between iterations
            for(NeuralNetConfiguration conf : this.conf.getConfs()) {
                conf.setNumIterations(1);
            }

            //Run learning, and average at each iteration
            for(int i = 0; i < iterations; i++) {
                runIteration(rdd);
            }

            //Reset number of iterations in config
            if(iterations > 1 ){
                for(NeuralNetConfiguration conf : this.conf.getConfs()) {
                    conf.setNumIterations(iterations);
                }
            }
        }

        return network;
    }

    private void runIteration(JavaRDD<DataSet> rdd) {

        log.info("Broadcasting initial parameters of length " + network.numParams(false));
        INDArray valToBroadcast = network.params(false);
        this.params = sc.broadcast(valToBroadcast);
        Updater updater = network.getUpdater();
        this.updater = sc.broadcast(updater);


        int paramsLength = network.numParams(false);
        boolean accumGrad = sc.getConf().getBoolean(ACCUM_GRADIENT,false);


        if(accumGrad) {
            //Learning via averaging gradients
            JavaRDD<Tuple2<Gradient,Updater>> results = rdd.mapPartitions(new GradientAccumFlatMap(conf.toJson(), this.params, this.updater),true).cache();
            log.info("Ran iterative reduce...averaging results now.");

            JavaRDD<Gradient> resultsGradient = results.map(new GradientFromTupleFunction());

            GradientAdder a = new GradientAdder(paramsLength);
            resultsGradient.foreach(a);
            INDArray accumulatedGradient = a.getAccumulator().value();
            boolean divideGrad = sc.getConf().getBoolean(DIVIDE_ACCUM_GRADIENT,false);
            if(divideGrad)
                accumulatedGradient.divi(results.partitions().size());
            log.info("Accumulated parameters");
            log.info("Summed gradients.");
            network.setParameters(network.params(false).addi(accumulatedGradient));
            log.info("Set parameters");

            log.info("Processing updaters");
            JavaRDD<Updater> resultsUpdater = results.map(new UpdaterFromGradientTupleFunction());

            UpdaterAggregator aggregator = resultsUpdater.aggregate(
                    resultsUpdater.first().getAggregator(false),
                    new UpdaterElementCombiner(),
                    new UpdaterAggregatorCombiner()
            );
            Updater combinedUpdater = aggregator.getUpdater();
            network.setUpdater(combinedUpdater);
            log.info("Set updater");
        }
        else {
            //Standard parameter averaging
            JavaRDD<Tuple2<INDArray,Updater>> results = rdd.mapPartitions(new IterativeReduceFlatMap(conf.toJson(),
                    this.params, this.updater, this.best_score_acc),true).cache();

            JavaRDD<INDArray> resultsParams = results.map(new INDArrayFromTupleFunction());
            log.info("Ran iterative reduce... averaging parameters now.");
            Adder a = new Adder(paramsLength);
            resultsParams.foreach(a);
            INDArray newParams = a.getAccumulator().value();
            log.info("Accumulated parameters");
            newParams.divi(rdd.partitions().size());
            log.info("Divided by partitions");
            network.setParameters(newParams);
            log.info("Set parameters");

            log.info("Processing updaters");
            JavaRDD<Updater> resultsUpdater = results.map(new UpdaterFromTupleFunction());

            UpdaterAggregator aggregator = resultsUpdater.aggregate(
                    resultsUpdater.first().getAggregator(false),
                    new UpdaterElementCombiner(),
                    new UpdaterAggregatorCombiner()
            );
            Updater combinedUpdater = aggregator.getUpdater();
            network.setUpdater(combinedUpdater);
            log.info("Set updater");
        }
    }


    /**
     * Train a multi layer network
     * @param data the data to train on
     * @param conf the configuration of the network
     * @return the fit multi layer network
     */
    public static MultiLayerNetwork train(JavaRDD<LabeledPoint> data,MultiLayerConfiguration conf) {
        SparkDl4jMultiLayer multiLayer = new SparkDl4jMultiLayer(data.context(),conf);
        return multiLayer.fit(new JavaSparkContext(data.context()),data);
    }
}
