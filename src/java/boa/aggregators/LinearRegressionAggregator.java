/*
 * Copyright 2014, Hridesh Rajan, Robert Dyer,
 *                 and Iowa State University of Science and Technology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package boa.aggregators;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import weka.classifiers.functions.LinearRegression;
import weka.core.Attribute;
import weka.core.FastVector;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Utils;

/**
 * A Boa aggregator for training the model using Linear Regression.
 * 
 * @author ankuraga
 */
@AggregatorSpec(name = "linearRegression", formalParameters = { "string" })
public class LinearRegressionAggregator extends MLAggregator {
	private Map<String, List<Double>> vectors = new HashMap<String, List<Double>>();
	private ArrayList<Double> vector = new ArrayList<Double>();
	private String[] options;
	private int count = 0;
	private int inc = 0;
	private LinearRegression model;

	public LinearRegressionAggregator(final String s) {
		super(s);
		try {
			options = Utils.splitOptions(s);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void aggregate(final String data, final String metadata) throws IOException, InterruptedException {	
		if(this.count != this.getVectorSize()) {
			this.vector.add(Double.parseDouble(data));
			this.count++;
		}

		if(this.count == this.getVectorSize()) {
			this.vectors.put("Vector "+this.inc, this.vector);
			this.inc++;
			this.vector = new ArrayList<Double>();
			this.count = 0;
		}
	}

	/** {@inheritDoc} */
	@Override
	public void finish() throws IOException, InterruptedException {
		int NumOfAttributes = this.getVectorSize();
		List<Attribute> attribute = new ArrayList<Attribute>();
		FastVector fvAttributes = new FastVector(NumOfAttributes);

		for(int i=0; i < NumOfAttributes; i++) {
			 attribute.add(new Attribute("Attribute" + i));
			 fvAttributes.addElement(attribute.get(i));
		}

		Instances trainingSet = new Instances("LinearRegression", fvAttributes, 1);
		trainingSet.setClassIndex(NumOfAttributes-1);

		for(List<Double> vector : this.vectors.values()) {
			Instance instance = new Instance(NumOfAttributes);
			for(int i=0; i<vector.size(); i++) {
				 instance.setValue((Attribute)fvAttributes.elementAt(i), vector.get(i));
			}
			trainingSet.add(instance);
		}

		try {
			this.model = new LinearRegression();
			this.model.setOptions(options);
			this.model.buildClassifier(trainingSet);
		} catch(Exception ex) {
		}

		this.saveModel(this.model);
	}
}
