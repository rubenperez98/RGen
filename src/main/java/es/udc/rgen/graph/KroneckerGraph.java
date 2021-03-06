package es.udc.rgen.graph;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.Iterator;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.TextOutputFormat;
import org.apache.hadoop.mapred.lib.NLineInputFormat;

import es.udc.rgen.*;
import es.udc.rgen.misc.Cell;
import es.udc.rgen.misc.Dummy;
import es.udc.rgen.misc.Utils;

public class KroneckerGraph {

	private static final Log log = LogFactory.getLog(KroneckerGraph.class.getName());
	
	public static final String NUM_MAPS = "mapreduce.kroneckergraph.nummaps";
	public static final String NODES_PER_MAP = "mapreduce.kroneckergraph.nodesmap";
	public static final String EDGES_PER_MAP = "mapreduce.kroneckergraph.edgesmap";
	public static final String NUM_NODES = "mapreduce.kroneckergraph.nodes";
	public static final String NUM_EDGES = "mapreduce.kroneckergraph.edges";
	public static final String DELIMETER = "mapreduce.output.textoutputformat.separator";
	public static final String ITERATIONS = "mapreduce.kroneckergraph.k";
	
	public static final String SEED_MATRIX_DIMENSION = "mapreduce.kroneckergraph.seeddim";
	public static final String SEED_MATRIX_SUM = "mapreduce.kroneckergraph.seedsum";
	public static final String SEED_MATRIX = "mapreduce.kroneckergraph.seedxy";
	
	private DataOptions options;

	private static final String NODES_DIR_NAME = "nodes";
	private static final String EDGES_DIR_NAME = "edges";

	private String cdelim = "\t";
	private int k = 1;
	private long nodes = 0;
	private long edges = 0;
	
	// Facebook graph seed matrix
    private double[][] seedMatrix = {{0.9999 , 0.5887},{0.6254 , 0.3676}};
    private double sumSeedMatrix = 0.0;
	
	private static final Random random = new Random(System.currentTimeMillis());

	private Dummy dummy;

	public KroneckerGraph (DataOptions options) {
		this.options = options;
		parseArgs(options.getRemainArgs());
	}

	private void parseArgs(String[] args) {
		
		for (int i=0; i<args.length; i++) {

			if ("-d".equals(args[i])) {
				cdelim = args[++i];
			} else if ("-sm".equals(args[i])) {
				
				int i_aux=i+1;
				
				if ("facebook".equals(args[i_aux])) {
					double[][] auxSeedMatrix = {{0.9999 , 0.5887},{0.6254 , 0.3676}};
					seedMatrix = auxSeedMatrix;
				} else if ("google".equals(args[i_aux])) {
					double[][] auxSeedMatrix = {{0.8305 , 0.5573},{0.4638 , 0.3021}};
					seedMatrix = auxSeedMatrix;
				} else if ("amazon".equals(args[i_aux])) {
					double[][] auxSeedMatrix = {{0.9532 , 0.5502},{0.4439 , 0.2511}};
					seedMatrix = auxSeedMatrix;
				} else {
					parseMatrix(args[i_aux]);
				}
				
				++i;
				
			} else if ("-k".equals(args[i])) {
				k = Integer.parseInt(args[++i]);
			} else {
				DataOptions.printUsage("Unknown Kronecker-graph data arguments --> " + args[i] + " <--");
			}
		}

	}
	
	public void parseMatrix(String args) {
		String[] rows = args.substring(1, args.length()-1).split(":");
		double[][] auxSeedMatrix = new double[rows.length][rows.length];
		for (int i=0;i<rows.length;i++) {
			String[] parsedRows = rows[i].split(",");
			for (int j=0;j<parsedRows.length;j++) {
				auxSeedMatrix[i][j]=Double.parseDouble(parsedRows[j]);
			}
		}
		seedMatrix = auxSeedMatrix;
	}
	
	public StringBuffer dumpSeedMatrix() {
		StringBuffer dump = new StringBuffer("[");
		for (int i=0;i<seedMatrix.length;i++) {
			for (int j=0;j<seedMatrix[i].length;j++) {
				dump.append(seedMatrix[i][j]).append(" ");
			}
			if (i<seedMatrix.length-1) {
				dump.append(":");
			} else {
				dump.append("]");
			}
		}
		return dump;
	}
	
	public void init() throws IOException {
		
		log.info("Initializing Kronecker-graph data generator...");
		
		Utils.checkHdfsPath(options.getResultPath(), true);
		Utils.checkHdfsPath(options.getWorkPath(), true);

		dummy = new Dummy(options.getWorkPath(), options.getNumMaps());
	}

	private void setKroneckerNodesOptions(JobConf job) {
		nodes = (long) Math.ceil(Math.pow(seedMatrix.length,k));
		
		double sum = 0;
		for (int i = 0;i<seedMatrix.length;i++) {
			for (int j=0;j<seedMatrix[i].length;j++) {
				sum += seedMatrix[i][j];
				job.setDouble(SEED_MATRIX.concat(String.valueOf(i)).concat(String.valueOf(j)), seedMatrix[i][j]);
			}
		}
		sumSeedMatrix = sum;
		job.setDouble(SEED_MATRIX_SUM, sumSeedMatrix);
		job.setInt(SEED_MATRIX_DIMENSION, seedMatrix.length);
		
		edges = (long) Math.ceil(Math.pow(sum,k));
		
		job.setLong(NUM_NODES, nodes);
		job.setLong(NUM_EDGES, edges);
		
		long nodes_map = (long) Math.ceil(nodes * 1.0 / options.getNumMaps());
		job.setLong(NODES_PER_MAP, nodes_map);
		
		job.setInt(ITERATIONS, k);
	}
	
	private void setKroneckerEdgesOptions(JobConf job) throws URISyntaxException {
		for (int i = 0;i<seedMatrix.length;i++) {
			for (int j=0;j<seedMatrix[i].length;j++) {
				job.setDouble(SEED_MATRIX.concat(String.valueOf(i)).concat(String.valueOf(j)), seedMatrix[i][j]);
			}
		}
		job.setDouble(SEED_MATRIX_SUM, sumSeedMatrix);
		job.setInt(SEED_MATRIX_DIMENSION, seedMatrix.length);
		
		job.setLong(NUM_NODES, nodes);
		job.setLong(NUM_EDGES, edges);
		
		long edges_map = (long) Math.ceil(edges * 1.0 / options.getNumMaps());
		job.setLong(EDGES_PER_MAP, edges_map);
		
		job.set(DELIMETER, cdelim);
		
		job.setInt(ITERATIONS, k);
	}
	
	public static long[] getRange(int slotId, long limit, long slotlimit) {
		long[] range = new long[2];
		range[0] = slotlimit * (slotId - 1);
		range[1] = range[0] + slotlimit;
		if (range[1] > limit) {
			range[1] = limit;
		}
		return range;
	}

	public static class DummyToNodesMapper extends MapReduceBase implements
	Mapper<LongWritable, Text, LongWritable, Text> {
		
		private long nodes, nodes_map;

		private void getOptions(JobConf job) {
			nodes = job.getLong(NUM_NODES, 0);
			nodes_map = job.getLong(NODES_PER_MAP, 0);
		}

		@Override
		public void configure(JobConf job) {
			getOptions(job);
		}

		@Override
		public void map(LongWritable key, Text value,
				OutputCollector<LongWritable, Text> output, Reporter reporter) throws IOException {
	
			int slotId = Integer.parseInt(value.toString().trim());
			long[] range = KroneckerGraph.getRange(slotId, nodes, nodes_map);
			
			for (long i=range[0]; i<range[1]; i++) {
				key.set(i);
				Text v = new Text(Long.toString(i));
				output.collect(key, v);
			}
		}
	}
	
	public static class DummyToEdgesMapper extends MapReduceBase implements
	Mapper<LongWritable, Text, Cell, IntWritable> {

		private int k, seed_matrix_dim;
		private long edges_map, nodes;
		private double seed_matrix_sum;
		
		private Cell probMatrix[];

		private void getOptions(JobConf job) {
			nodes = job.getLong(NUM_NODES, 0);
			edges_map = job.getLong(EDGES_PER_MAP, 0);
			k = job.getInt(ITERATIONS, 0);
			seed_matrix_dim = job.getInt(SEED_MATRIX_DIMENSION, 0);
			seed_matrix_sum = job.getDouble(SEED_MATRIX_SUM, 0);
			
			
			probMatrix=new Cell[(int) Math.pow(seed_matrix_dim,2)];
			double cumProb = 0.0;
			int i = 0;
			for (int r=0;r<seed_matrix_dim;r++) {
				for (int c=0;c<seed_matrix_dim;c++) {
					double prob = job.getDouble(SEED_MATRIX.concat(String.valueOf(r)).concat(String.valueOf(c)), 0);
					//log.info("-----------------------------> SEED MATRIX ("+r+c+") --> "+job.getDouble(SEED_MATRIX.concat(String.valueOf(r)).concat(String.valueOf(c)), 0));
					if (prob > 0.0) {
						cumProb += prob;
						probMatrix[i]=new Cell(cumProb/seed_matrix_sum,r,c);
						i++;
					}
				}
			}
		}

		public void configure(JobConf job) {
			getOptions(job);
		}
	
		public void map(LongWritable key, Text value, OutputCollector<Cell, IntWritable> output,
				Reporter reporter) throws IOException{
			
			long rng=0,row=0,col=0,auxRow=0,auxCol=0;
			int n=0;
			double prob=0;

			for (int edges=0;edges<edges_map;edges++) {
				
				rng=nodes; row=0; col=0;
				
				for (int iter=0;iter<k;iter++) {
					
					prob=random.nextDouble();
					n=0;
					while(prob>probMatrix[n].getProb()) { n++;}
					
					auxRow=probMatrix[n].getRow();
					auxCol=probMatrix[n].getCol();
					
					rng/=seed_matrix_sum;
					row+=auxRow*rng;
					col+=auxCol*rng;
				}
				
				Cell cell = new Cell(0,row,col);
				
				output.collect(cell, new IntWritable(1));
			}
		}
	}
	
	public static class EdgesReducer extends MapReduceBase implements
	Reducer<Cell, IntWritable, LongWritable, LongWritable> {

		@Override
		public void reduce(Cell key, Iterator<IntWritable> values, OutputCollector<LongWritable, LongWritable> output,
				Reporter reporter) throws IOException {
			output.collect(new LongWritable(key.getRow()),new LongWritable(key.getCol()));
		}
	}

	private void createKroneckerNodes() throws IOException {

		log.info("Creating Kronecker-graph nodes...", null);

		Path fout = new Path(options.getResultPath(), NODES_DIR_NAME);
		
		JobConf job = new JobConf(KroneckerGraph.class);
		String jobname = "Create Kronecker-graph nodes";

		job.setJobName(jobname);
		setKroneckerNodesOptions(job);

		job.setOutputKeyClass(IntWritable.class);
		job.setOutputValueClass(Text.class);
		
		FileInputFormat.setInputPaths(job, dummy.getPath());
		job.setInputFormat(NLineInputFormat.class);
		
		job.setMapOutputKeyClass(Text.class);
		job.setMapperClass(DummyToNodesMapper.class);
		job.setNumReduceTasks(0);

		if (options.isSequenceOut()) {
			job.setOutputFormat(SequenceFileOutputFormat.class);
		} else {
			job.setOutputFormat(TextOutputFormat.class);
		}
		FileOutputFormat.setOutputPath(job, fout);

		log.info("Nodes will be created: "+job.get(NUM_NODES), null);
		log.info("Nodes per map: "+job.get(NODES_PER_MAP), null);
		log.info("K iterations: "+job.get(ITERATIONS),null);
		
		log.info("Running Job: " +jobname);
		log.info("Dummy file " + dummy.getPath() + " as input");
		log.info("Vertices file " + fout + " as output");
		Date startTime = new Date();
	    log.info("Job started: " + startTime);
		JobClient.runJob(job);
		Date endTime = new Date();
	    log.info("Job ended: " + endTime);
	    log.info("The job took " + 
	                       (endTime.getTime() - startTime.getTime()) /1000 + 
	                       " seconds.");
		log.info("Finished Running Job: " + jobname);
	}

	private void createKroneckerLinks() throws IOException, URISyntaxException {

		log.info("Creating Kronecker-graph edges...", null);

		JobConf job = new JobConf(KroneckerGraph.class);
		String jobname = "Create kronecker edges";

		Path fout = new Path(options.getResultPath(), EDGES_DIR_NAME);

		job.setJobName(jobname);
		setKroneckerEdgesOptions(job);

		job.setOutputKeyClass(Cell.class);
		job.setOutputValueClass(Text.class);
		
		FileInputFormat.setInputPaths(job, dummy.getPath());
		job.setInputFormat(NLineInputFormat.class);

		job.setMapperClass(DummyToEdgesMapper.class);
		job.setReducerClass(EdgesReducer.class);
		
		job.setMapOutputKeyClass(Cell.class);
		job.setMapOutputValueClass(IntWritable.class);

		if (options.getNumReds() > 0) {
			job.setNumReduceTasks(options.getNumReds());
		} else {
			job.setNumReduceTasks(1);
		}

		if (options.isSequenceOut()) {
			job.setOutputFormat(SequenceFileOutputFormat.class);
		} else {
			job.setOutputFormat(TextOutputFormat.class);
		}
		
		FileOutputFormat.setOutputPath(job, fout);
		
		log.info("Nodes created: "+job.get(NUM_NODES), null);
		log.info("Edges will be created: "+job.get(NUM_EDGES), null);
		log.info("Edges per map: "+job.get(EDGES_PER_MAP), null);
		log.info("K iterations: "+job.get(ITERATIONS),null);
		log.info("Seed matrix: "+dumpSeedMatrix(),null);
		
		
		log.info("Running Job: " +jobname);
		log.info("Dummy file " + dummy.getPath() + " as input");
		log.info("Edges file " + fout + " as output");
		Date startTime = new Date();
	    log.info("Job started: " + startTime);
		JobClient.runJob(job);
		Date endTime = new Date();
	    log.info("Job ended: " + endTime);
	    log.info("The job took " + 
	                       (endTime.getTime() - startTime.getTime()) /1000 + 
	                       " seconds.");
		log.info("Finished Running Job: " + jobname);
	}

	public void generate() throws IOException, URISyntaxException {
		
		log.info("Generating Kronecker-graph data files...");
		init();
		Date startTime = new Date();
	    log.info("Jobs started: " + startTime);
		createKroneckerNodes();
		createKroneckerLinks();
		Date endTime = new Date();
		log.info("Jobs ended: " + endTime);
	    log.info("The jobs took " + 
	                       (endTime.getTime() - startTime.getTime()) /1000 + 
	                       " seconds.");
		closeGenerator();
	}

	private void closeGenerator() throws IOException {

		log.info("Closing Kronecker-graph generator...");
		Utils.checkHdfsPath(options.getWorkPath(), true);
	}
}
