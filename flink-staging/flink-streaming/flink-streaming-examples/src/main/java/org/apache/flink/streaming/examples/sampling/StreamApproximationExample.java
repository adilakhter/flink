/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.examples.sampling;

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.IterativeDataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.function.sink.RichSinkFunction;
import org.apache.flink.streaming.api.function.source.RichSourceFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.api.java.tuple.*;

import java.util.Random;

/**
 * Created by marthavk on 2015-03-13.
 */
public class StreamApproximationExample {

    public static long MAX_COUNT = 10000;

    public StreamApproximationExample() throws Exception {

        //set execution environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        evaluationThroughIterator(env);
                env.execute();
    } //end of StreamApproximationExample cTor




    public static void main(String args[]) throws Exception {
        new StreamApproximationExample();
    }

    public static void evaluationThroughIterator(StreamExecutionEnvironment env) {

        //set execution environment
        //final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        //set sample size
        final int rSize = 100;

        //set parameters for random evolving gaussian sample generator
        final double meanInit = 0;
        final double sigmaInit = 1;
        final double mstep = 0.1;
        final double sstep = 0;
        final int interval = 10;
        final Parameters initParams = new Parameters(meanInit, sigmaInit, mstep, sstep, interval);

        //create source
        DataStreamSource<Tuple3<Double, Distribution, Boolean>> source = generateStream(env, initParams);

        //create iteration
        IterativeDataStream<Tuple3<Double, Distribution, Boolean>> iteration = source.iterate();

        //define iteration head
        DataStream<Tuple2<Double, Boolean>> head = iteration.map(new MapFunction<Tuple3<Double, Distribution, Boolean>, Tuple2<Double, Boolean>>() {

            Distribution currentDist = new Distribution(initParams.getMeanInit(), initParams.getSigmaInit());

            @Override
            public Tuple2<Double, Boolean> map(Tuple3<Double, Distribution, Boolean> value) throws Exception {
                if (value.f2) {
                    // must save latest distribution value

                    currentDist = value.f1;

                    // must be forwarded to sampler (flag should be true to pass the isFeedback filter)
                    return new Tuple2<Double, Boolean>(value.f0, true);
                } else {

                    // must be compared with latest distribution value and evaluated
                    double distance = Evaluator.evaluate(currentDist, value.f1);
                    return new Tuple2<Double, Boolean>(distance, false);
                }

            }
        });

        //define iteration tail
        DataStream<Tuple3<Double, Distribution, Boolean>> tail = head.filter(new FilterFunction<Tuple2<Double, Boolean>>() {
            @Override
            //allow tuples generated from source to pass to the sampling phase
            //is feedback
            public boolean filter(Tuple2<Double, Boolean> value) throws Exception {
                return value.f1;
            }
            //SAMPLING ALGORITHM
            //RESERVOIR SAMPLING
        }).map(new MapFunction<Tuple2<Double, Boolean>, Tuple3<Double, Distribution, Boolean>>() {
            Reservoir<Double> r = new Reservoir<Double>(rSize);
            int count = 0;

            @Override
            public Tuple3<Double, Distribution, Boolean> map(Tuple2<Double, Boolean> value) throws Exception {
                count++;
                if (Coin.flip(count / rSize)) {
                    r.insertElement(value.f0);
                }

                return new Tuple3<Double, Distribution, Boolean>(null, new Distribution(r), false);
            }
        });

        //close iteration with tail
        iteration.closeWith(tail);

        //filter out results from evaluator and sink
        head.filter(new FilterFunction<Tuple2<Double, Boolean>>() {
            @Override
            public boolean filter(Tuple2<Double, Boolean> value) throws Exception {
                return !value.f1;
            }
        }).addSink(new RichSinkFunction<Tuple2<Double, Boolean>>() {
            @Override
            public void invoke(Tuple2<Double, Boolean> value) throws Exception {
                System.out.println("Distance: " + value.f0);
            }

            @Override
            public void cancel() {

            }
        });

    }
    

    private static DataStreamSource<Tuple3<Double, Distribution, Boolean>> generateStream (StreamExecutionEnvironment env, final Parameters initParams) {
        return env.addSource(new RichSourceFunction<Tuple3<Double, Distribution, Boolean>>() {

            long count = 0;
            Distribution gaussD = new Distribution (initParams.getMeanInit(), initParams.getSigmaInit());

            @Override
            public void run(Collector<Tuple3<Double, Distribution, Boolean>> collector) throws Exception {

                while(count<MAX_COUNT) {
                    count++;
                    gaussD.updateMean(count, initParams.getmStep(), initParams.getInterval());
                    gaussD.updateSigma(count, initParams.getsStep(), initParams.getInterval());
                    double newItem = Gaussian.nextGaussian(gaussD.getMean(), gaussD.getSigma());

                    collector.collect(new Tuple3<Double, Distribution, Boolean>(newItem, gaussD, true));
                }
            }

            @Override
            public void cancel() {

            }
        });
    }


    // not used
    private DataStream<Reservoir<Double>> simpleReservoirSampling(DataStream<Double> dataStream, StreamExecutionEnvironment env, final int rSize) {
        return dataStream.map(new MapFunction<Double, Reservoir<Double>>() {
            Reservoir<Double> r = new Reservoir<Double>(rSize);
            int count = 0;

            @Override
            public Reservoir<Double> map(Double aDouble) throws Exception {
                count++;
                if (Coin.flip(count / rSize)) {
                    r.insertElement(aDouble);
                }
                return r;
            }

        });
    }



    private static final class Coin {
        public static boolean flip(int sides) {
            return (Math.random() * sides < 1);
        }
    }

    private static final class Gaussian {
        public static double nextGaussian(double mean, double stDev) { return (new Random().nextGaussian()*stDev + mean); }
    }

}
