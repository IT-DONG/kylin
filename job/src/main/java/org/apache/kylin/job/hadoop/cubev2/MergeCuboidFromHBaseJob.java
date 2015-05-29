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

package org.apache.kylin.job.hadoop.cubev2;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.cli.Options;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.HFileOutputFormat;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.Bytes;
import org.apache.kylin.common.util.StringSplitter;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.job.constant.BatchConstants;
import org.apache.kylin.job.hadoop.cube.CuboidJob;

/**
 * @author shaoshi
 */
public class MergeCuboidFromHBaseJob extends CuboidJob {

    @Override
    public int run(String[] args) throws Exception {
        Options options = new Options();

        try {
            options.addOption(OPTION_JOB_NAME);
            options.addOption(OPTION_CUBE_NAME);
            options.addOption(OPTION_SEGMENT_NAME);
            options.addOption(OPTION_INPUT_PATH);
            options.addOption(OPTION_OUTPUT_PATH);
            options.addOption(OPTION_HTABLE_NAME);
            parseOptions(options, args);

            String cubeName = getOptionValue(OPTION_CUBE_NAME).toUpperCase();
            String segmentName = getOptionValue(OPTION_SEGMENT_NAME).toUpperCase();
            String htableName = getOptionValue(OPTION_HTABLE_NAME).toUpperCase();
            KylinConfig config = KylinConfig.getInstanceFromEnv();
            CubeManager cubeMgr = CubeManager.getInstance(config);
            CubeInstance cube = cubeMgr.getCube(cubeName);


            Configuration conf = this.getConf();
            HBaseConfiguration.merge(conf, HBaseConfiguration.create(conf));

            // start job
            String jobName = getOptionValue(OPTION_JOB_NAME);
            System.out.println("Starting: " + jobName);
            job = Job.getInstance(conf, jobName);

            setJobClasspath(job);

            Path output = new Path(getOptionValue(OPTION_OUTPUT_PATH));
            FileOutputFormat.setOutputPath(job, output);


            List<Scan> scans = new ArrayList<Scan>();
            for (String htable : StringSplitter.split(getOptionValue(OPTION_INPUT_PATH), ",")) {
                Scan scan = new Scan();
                scan.setCaching(500);        // 1 is the default in Scan, which will be bad for MapReduce jobs
                scan.setCacheBlocks(false);  // don't set to true for MR jobs
                scan.setAttribute(Scan.SCAN_ATTRIBUTES_TABLE_NAME, Bytes.toBytes(htable));
                scans.add(scan);
            }

            TableMapReduceUtil.initTableMapperJob(scans, MergeCuboidFromHBaseMapper.class, ImmutableBytesWritable.class,
                    Text.class, job);

            /*
            // Reducer - only one
            job.setReducerClass(CuboidReducer.class);
            job.setOutputFormatClass(SequenceFileOutputFormat.class);
            job.setOutputKeyClass(Text.class);
            job.setOutputValueClass(Text.class);

*/

            // set job configuration
            job.getConfiguration().set(BatchConstants.CFG_CUBE_NAME, cubeName);
            job.getConfiguration().set(BatchConstants.CFG_CUBE_SEGMENT_NAME, segmentName);

            // add metadata to distributed cache
            attachKylinPropsAndMetadata(cube, job.getConfiguration());


            HTable htable = new HTable(conf, htableName);
            HFileOutputFormat.configureIncrementalLoad(job,
                    htable);


            // set Reducer; This need be after configureIncrementalLoad, to overwrite the default reducer class
            job.setReducerClass(InMemCuboidReducer.class);
            job.setOutputKeyClass(ImmutableBytesWritable.class);
            job.setOutputValueClass(KeyValue.class);

            this.deletePath(job.getConfiguration(), output);

            return waitForCompletion(job);
        } catch (
                Exception e
                )

        {
            logger.error("error in MergeCuboidFromHBaseJob", e);
            printUsage(options);
            throw e;
        }

    }

}