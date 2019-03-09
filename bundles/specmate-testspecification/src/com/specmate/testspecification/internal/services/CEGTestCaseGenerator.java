package com.specmate.testspecification.internal.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil.EqualityHelper;
import org.sat4j.core.VecInt;
import org.sat4j.maxsat.SolverFactory;
import org.sat4j.maxsat.WeightedMaxSatDecorator;
import org.sat4j.pb.IPBSolver;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.ISolver;
import org.sat4j.specs.IVecInt;
import org.sat4j.specs.TimeoutException;
import org.sat4j.tools.GateTranslator;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.rationals.Rational;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.Formula;
import org.sosy_lab.java_smt.api.FormulaManager;
import org.sosy_lab.java_smt.api.IntegerFormulaManager;
import org.sosy_lab.java_smt.api.Model;
import org.sosy_lab.java_smt.api.Model.ValueAssignment;
import org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula;
import org.sosy_lab.java_smt.api.NumeralFormula.RationalFormula;
import org.sosy_lab.java_smt.api.OptimizationProverEnvironment;
import org.sosy_lab.java_smt.api.OptimizationProverEnvironment.OptStatus;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.RationalFormulaManager;
import org.sosy_lab.java_smt.api.SolverContext;
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions;
import org.sosy_lab.java_smt.api.SolverException;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.specmate.common.AssertUtil;
import com.specmate.common.SpecmateException;
import com.specmate.model.base.BasePackage;
import com.specmate.model.base.IContainer;
import com.specmate.model.base.IModelConnection;
import com.specmate.model.base.IModelNode;
import com.specmate.model.requirements.CEGConnection;
import com.specmate.model.requirements.CEGModel;
import com.specmate.model.requirements.CEGNode;
import com.specmate.model.requirements.NodeType;
import com.specmate.model.support.util.SpecmateEcoreUtil;
import com.specmate.model.testspecification.ParameterAssignment;
import com.specmate.model.testspecification.ParameterType;
import com.specmate.model.testspecification.TestCase;
import com.specmate.model.testspecification.TestParameter;
import com.specmate.model.testspecification.TestSpecification;
import com.specmate.model.testspecification.TestspecificationFactory;
import com.specmate.testspecification.internal.services.TaggedBoolean.ETag;

public class CEGTestCaseGenerator extends TestCaseGeneratorBase<CEGModel, CEGNode> {
	
	private static List<BooleanFormula> booleanList = new ArrayList<BooleanFormula>();
	private static HashMap<String, BooleanFormula> booleanVariables = new HashMap<String, BooleanFormula>();
	private static List<BooleanFormula> booleanListForZ3 = new ArrayList<BooleanFormula>();
	private static HashMap<String, BooleanFormula> nodeVariables = new HashMap<String, BooleanFormula>();
	private static HashMap<String, RationalFormula> rationalVariables = new HashMap<String, RationalFormula>();
	private static List<IntegerFormula> integerList = new ArrayList<IntegerFormula>();
	HashMap<IModelNode, Formula> nodeMap = new HashMap<>();
	HashMap<IModelNode, Formula> nodeToMeaningMap = new HashMap<>();
	HashMap<IModelNode, Formula> nodeToMeaningMapForZ3 = new HashMap<>();
	private static HashMap<Integer, List<BooleanFormula>> mergeCanditatesList = new HashMap<Integer, List<BooleanFormula>>();
	Map<Integer, NodeEvaluation> int2EvaluationMap = null;
	
	// TODO: make private fields and initialize them in the constructer with the context 
	private static FormulaManager fmgr = null;
	private static BooleanFormulaManager bmgr = null;
	private static IntegerFormulaManager imgr = null;
	private static RationalFormulaManager rmgr = null;
	
	private static SolverContext context = null;
	
	public CEGTestCaseGenerator(TestSpecification specification) {
		super(specification, CEGModel.class, CEGNode.class);		
		
		initSMTSolver();
		// no return type needed
		classifyCEG();
	}
	
	@Override
	protected void generateParameters() {
		for (IModelNode node : nodes) {
			String name = ((CEGNode) node).getVariable();
			ParameterType type = determineParameterTypeForNode(node);
			if (type != null && !parameterExists(specification, name, type)) {
				TestParameter parameter = createTestParameter(name, type);
				specification.getContents().add(parameter);
			}
		}
	}
	
	

	/**
	 * Determines if a node is an input, output or intermediate node.
	 * 
	 * @param node
	 * @return ParameterType.INPUT, if the nodes is an input node,
	 *         ParameterType.OUTPUT, if the node is an output node,
	 *         <code>null</code> if the node is an intermediate node.
	 */
	private ParameterType determineParameterTypeForNode(IModelNode node) {
		if (node.getIncomingConnections().isEmpty()) {
			return ParameterType.INPUT;
		} else if (node.getOutgoingConnections().isEmpty()) {
			return ParameterType.OUTPUT;
		} else {
			return null;
		}
	}

	/** Checks if a parameter already exists in a specification. */
	private boolean parameterExists(TestSpecification specification, String name, ParameterType type) {
		List<TestParameter> parameters = SpecmateEcoreUtil.pickInstancesOf(specification.getContents(),
				TestParameter.class);
		for (TestParameter parameter : parameters) {
			if (parameter.getName().equals(name) && parameter.getType().equals(type)) {
				return true;
			}
		}
		return false;
	}

	/** Generates test cases for the nodes of a CEG. */
	@Override
	protected void generateTestCases() throws SpecmateException {
		Pair<Set<NodeEvaluation>, Set<NodeEvaluation>> evaluations = computeEvaluations();
		Set<NodeEvaluation> consistent = evaluations.getLeft();
		Set<NodeEvaluation> inconsistent = evaluations.getRight();
		int position = 0;
		for (NodeEvaluation evaluation : consistent) {
			TestCase testCase = createTestCase(evaluation, specification, true);
			testCase.setPosition(position++);
			specification.getContents().add(testCase);
		}
		List<TestCase> inconsistentTestCases = new ArrayList<TestCase>();
		for (NodeEvaluation evaluation : inconsistent) {
			TestCase testCase = createTestCase(evaluation, specification, false);
			boolean newTc = !inconsistentTestCases.stream().anyMatch(tc -> {
				EqualityHelper helper = new IdNamePositionIgnoreEqualityHelper();
				return helper.equals(tc, testCase);
			});
			if (newTc) {
				inconsistentTestCases.add(testCase);
				testCase.setPosition(position++);
				specification.getContents().add(testCase);
			} else {
				SpecmateEcoreUtil.detach(testCase);
			}
		}
	}

	/** Creates a test case for a single node evaluation. */
	private TestCase createTestCase(NodeEvaluation evaluation, TestSpecification specification, boolean isConsistent) {
		TestCase testCase = super.createTestCase(specification);
		testCase.setConsistent(isConsistent);
		List<TestParameter> parameters = SpecmateEcoreUtil.pickInstancesOf(specification.getContents(),
				TestParameter.class);
		Multimap<String, IContainer> variableToNodeMap = ArrayListMultimap.create();
		evaluation.keySet().stream().forEach(n -> variableToNodeMap.put(((CEGNode) n).getVariable(), n));
		for (TestParameter parameter : parameters) {
			List<String> constraints = new ArrayList<>();
			for (IContainer node : variableToNodeMap.get(parameter.getName())) {
				TaggedBoolean nodeEval = evaluation.get(node);
				String condition = ((CEGNode) node).getCondition();
				if (nodeEval != null) {
					String parameterValue = buildParameterValue(condition, nodeEval.value);
					constraints.add(parameterValue);
				}
			}
			String parameterValue = StringUtils.join(constraints, ",");
			ParameterAssignment assignment = TestspecificationFactory.eINSTANCE.createParameterAssignment();
			assignment.setId(SpecmateEcoreUtil.getIdForChild(testCase, assignment.eClass()));
			assignment.setParameter(parameter);
			assignment.setValue(parameterValue);
			assignment.setCondition(parameterValue);
			testCase.getContents().add(assignment);
		}
		return testCase;
	}

	/**
	 * Creates the string representation of an operator and a value. Negates the
	 * operator if necessary.
	 */
	private String buildParameterValue(String condition, Boolean nodeEval) {
		if (!nodeEval) {
			return negateCondition(condition);
		}
		return condition;
	}

	/** Negates a condition. */
	private String negateCondition(String condition) {
		return "not " + condition;
	}

	/**
	 * Node evaluations are a precursor to test cases. This method computes the node
	 * evaluations according to the rules in the Specmate systems requirements
	 * documentation.
	 * 
	 * @param nodes
	 * @return
	 * @throws SpecmateException
	 */
	private Pair<Set<NodeEvaluation>, Set<NodeEvaluation>> computeEvaluations() throws SpecmateException {
		Set<NodeEvaluation> consistentEvaluations = getInitialEvaluations();
		Set<NodeEvaluation> inconsistentEvaluations = new HashSet<>();
		Set<NodeEvaluation> intermediateEvaluations = getIntermediateEvaluations(consistentEvaluations);
		while (!intermediateEvaluations.isEmpty()) {
			for (NodeEvaluation evaluation : intermediateEvaluations) {
				consistentEvaluations.remove(evaluation);
				Optional<IModelNode> intermediateNodeOpt = getAnyIntermediateNode(evaluation);
				AssertUtil.assertTrue(intermediateNodeOpt.isPresent());
				IModelNode node = intermediateNodeOpt.get();
				Pair<Set<NodeEvaluation>, Set<NodeEvaluation>> iterationResult = iterateEvaluation(evaluation, node);
				consistentEvaluations.addAll(iterationResult.getLeft());
				inconsistentEvaluations.addAll(iterationResult.getRight());
			}
			intermediateEvaluations = getIntermediateEvaluations(consistentEvaluations);
		}

		Pair<Set<NodeEvaluation>, Set<NodeEvaluation>> refinedEvaluations = refineEvaluations(consistentEvaluations);
		refinedEvaluations.getRight().addAll(inconsistentEvaluations);
		return refinedEvaluations;
	}

	private Pair<Set<NodeEvaluation>, Set<NodeEvaluation>> refineEvaluations(Set<NodeEvaluation> evaluationList)
			throws SpecmateException {
		Pair<Set<NodeEvaluation>, Set<NodeEvaluation>> mergedEvals = mergeCompatibleEvaluations(evaluationList);
		Set<NodeEvaluation> merged = mergedEvals.getLeft();
		Set<NodeEvaluation> inconsistent = mergedEvals.getRight();
		Set<NodeEvaluation> filled = new HashSet<>();
		for (NodeEvaluation eval : merged) {
			NodeEvaluation temp = fill(eval);
			if (temp.isEmpty()) {
				continue;
			}
			filled.add(temp);
		}
		return Pair.of(filled, inconsistent);
	}

	/**
	 * Returns the inital evaluations for the CEG model, where all output nodes are
	 * set one time true and one time false.
	 */
	private Set<NodeEvaluation> getInitialEvaluations() {
		Set<NodeEvaluation> evaluations = new HashSet<>();
		nodes.stream().filter(node -> (determineParameterTypeForNode(node) == ParameterType.OUTPUT)).forEach(node -> {
			NodeEvaluation positiveEvaluation = new NodeEvaluation();
			positiveEvaluation.put(node, new TaggedBoolean(true, TaggedBoolean.ETag.ALL));
			evaluations.add(positiveEvaluation);
			NodeEvaluation negativeEvaluation = new NodeEvaluation();
			negativeEvaluation.put(node, new TaggedBoolean(false, TaggedBoolean.ETag.ALL));
			evaluations.add(negativeEvaluation);
		});

		return evaluations;
	}

	/** Retrieves a node that has predecessors with out a set value */
	private Optional<IModelNode> getAnyIntermediateNode(NodeEvaluation evaluation) {
		for (Entry<IContainer, TaggedBoolean> entry : evaluation.entrySet()) {
			if (entry.getValue().tag == ETag.ANY) {
				continue;
			}
			IModelNode node = (IModelNode) entry.getKey();
			if (determineParameterTypeForNode(node) != ParameterType.INPUT) {
				boolean handled = node.getIncomingConnections().stream().map(conn -> conn.getSource())
						.allMatch(n -> evaluation.containsKey(n));
				if (!handled) {
					return Optional.of(node);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Returns evaluations that have intermediate nodes (i.e. nodes that have to be
	 * evaluated)
	 */
	private Set<NodeEvaluation> getIntermediateEvaluations(Set<NodeEvaluation> evaluations) {
		HashSet<NodeEvaluation> intermediate = new HashSet<>();
		for (NodeEvaluation evaluation : evaluations) {
			if (getAnyIntermediateNode(evaluation).isPresent()) {
				intermediate.add(evaluation);
			}
		}
		return intermediate;
	}

	/**
	 * Takes evaluation and a node and computes the evaluations of the nodes
	 * predecessors
	 */
	private Pair<Set<NodeEvaluation>, Set<NodeEvaluation>> iterateEvaluation(NodeEvaluation evaluation, IModelNode node)
			throws SpecmateException {
		Set<NodeEvaluation> consistent = new HashSet<>();
		Set<NodeEvaluation> inconsistent = new HashSet<>();
		AssertUtil.assertEquals(evaluation.get(node).tag, ETag.ALL);
		switch (((CEGNode) node).getType()) {
		case AND:
			handleAllCase(true, evaluation, node, consistent, inconsistent);
			break;
		case OR:
			handleAllCase(false, evaluation, node, consistent, inconsistent);
			break;
		}
		return Pair.of(consistent, inconsistent);
	}

	private void handleAllCase(boolean isAnd, NodeEvaluation evaluation, IModelNode node,
			Set<NodeEvaluation> consistent, Set<NodeEvaluation> inconsistent) throws SpecmateException {
		boolean nodeValue = evaluation.get(node).value;
		boolean failure;
		// case where node is true in AND case or node is false in OR case
		if ((isAnd && nodeValue) || (!isAnd && !nodeValue)) {
			for (IModelConnection selectedConn : node.getIncomingConnections()) {
				NodeEvaluation newEvaluation = (NodeEvaluation) evaluation.clone();
				failure = false;
				for (IModelConnection conn : node.getIncomingConnections()) {
					boolean value = isAnd ^ ((CEGConnection) conn).isNegate();
					ETag tag = conn == selectedConn ? ETag.ALL : ETag.ANY;
					failure = failure
							|| !checkAndSet(newEvaluation, (CEGNode) conn.getSource(), new TaggedBoolean(value, tag));
				}
				if (!failure) {
					consistent.add(newEvaluation);
				} else {
					inconsistent.add(newEvaluation);
				}
			}
			// case where node is false in AND case or node is true in OR case
		} else {
			for (IModelConnection selectedConn : node.getIncomingConnections()) {
				NodeEvaluation newEvaluation = (NodeEvaluation) evaluation.clone();
				failure = false;
				for (IModelConnection conn : node.getIncomingConnections()) {
					boolean value = ((conn == selectedConn) ^ (isAnd ^ ((CEGConnection) conn).isNegate()));
					ETag tag = conn == selectedConn ? ETag.ALL : ETag.ANY;
					failure = failure
							|| !checkAndSet(newEvaluation, (CEGNode) conn.getSource(), new TaggedBoolean(value, tag));
				}
				if (!failure) {
					consistent.add(newEvaluation);
				} else {
					inconsistent.add(newEvaluation);
				}
			}
		}
	}

	/**
	 * Sets the value of a node in an evaluation but checks first if it is already
	 * set with a different value
	 * 
	 * @return false if an inconsistent value would be set in the node
	 */
	private boolean checkAndSet(NodeEvaluation evaluation, CEGNode node, TaggedBoolean effectiveValue)
			throws SpecmateException {
		if (evaluation.containsKey(node) && evaluation.get(node).value != effectiveValue.value) {
			return false;
		} else {
			evaluation.put(node, effectiveValue);
			return true;
		}
	}

	/**
	 * Runs through the list of evaluations and merges the ones that can be merged.
	 * Identifiey inconsistent evaluations
	 * 
	 * @throws SpecmateException
	 */
	private Pair<Set<NodeEvaluation>, Set<NodeEvaluation>> mergeCompatibleEvaluations(Set<NodeEvaluation> evaluations)
			throws SpecmateException {
		Set<NodeEvaluation> result = new HashSet<>();
		while (evaluations.size() > 0) {
			Set<NodeEvaluation> candidates = getMergeCandiate(evaluations);

			if (candidates.isEmpty()) {
				// There is no merge candidate:
				// The model has contradictory constraints e.g. (A ==> X) & (A
				// ==> !X)
				// Remaining evaluations are inconsistent
				return Pair.of(result, evaluations);
			}

			evaluations.removeAll(candidates);
			NodeEvaluation merged = mergeAllEvaluations(candidates);
			result.add(merged);
		}
		return Pair.of(result, new HashSet<>());
	}

	private Set<NodeEvaluation> getMergeCandiate(Set<NodeEvaluation> evaluations) throws SpecmateException {
		// Map to track between logical variables and evaluations
		Map<Integer, NodeEvaluation> var2EvalMap = new HashMap<>();

		// Inititalize solver infrastructure
		
		/*PBSolver solver = org.sat4j.pb.SolverFactory.newResolution();
		GateTranslator translator = new GateTranslator(solver);
		WeightedMaxSatDecorator maxSat = new WeightedMaxSatDecorator(solver);
		 */
		
		// We will need evaluations.size()+1 new variables, one set of varibles
		// e_n to switch on and off each evaluation and one variable s to enable
		// the implications s <==> (e=>n) where n is the evaluation result for a
		// certain node.
		// see pushEvaluations for the details

		//int maxVar = getAdditionalVar(evaluations.size() + 1);
		//maxSat.newVar(maxVar);

		try {
			pushCEGStructure();
			var2EvalMap = pushEvaluations(evaluations);
		} catch (ContradictionException c) {
			throw new SpecmateException(c);
		}
		
		return findModelWithZ3(var2EvalMap);
	}
	

	private Set<NodeEvaluation> findModelWithZ3(Map<Integer, NodeEvaluation> var2EvalMap) {
		Set<NodeEvaluation> mergeCandidates = new HashSet<>();
		
		if(var2EvalMap.isEmpty()) {
			return mergeCandidates;
		}
		
		try (OptimizationProverEnvironment optProver = context.newOptimizationProverEnvironment(ProverOptions.GENERATE_MODELS)) {

		    IntegerFormula scoreBasic = imgr.makeNumber(0);
		    IntegerFormula scoreHigh = imgr.makeNumber(1);
		    
		    
		    /*
		     * add the structure of the CEG as a hard constraint
		     */
		    for(BooleanFormula b: booleanListForZ3) {
		    	try {
					optProver.addConstraint(b);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}	
		    }
		    
		    /*
		     * add the meaning of the nodes as a hard constraint, as they are not dependent from the evaluations
		     */
		    for(Formula f: nodeToMeaningMapForZ3.values()) {
		    	try {
					optProver.addConstraint((BooleanFormula) f);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}	
		    }
		    		    
		    
		    /*
		     * 	TODO:
		     * 	Before maximizing check if all the evaluations can be satisfied (see listIsSat from SMTInterpolOpimiztation branch)
		     */
		    BooleanFormula a;
		    List<IntegerFormula> optWeights = Lists.newArrayList();
		    List<BooleanFormula> evaluationVariables = Lists.newArrayList();
		    for(Integer index: var2EvalMap.keySet()) {
		    	a = mergeCanditatesList.get(index).get(0);
		    	
		    	for(int i = 1; i < mergeCanditatesList.get(index).size(); i++) {
		    		a = bmgr.and(mergeCanditatesList.get(index).get(i), a);
		    	}
		    	BooleanFormula numberOfEval = bmgr.makeVariable(index.toString() + "Evaluation");
		    	evaluationVariables.add(numberOfEval);
		    	a = bmgr.equivalence(a, numberOfEval);
		    	optWeights.add(bmgr.ifThenElse(a, scoreHigh, scoreBasic));
		    }
		    BooleanFormula evalNodeFormula  = null; 
		    for(BooleanFormula formula: evaluationVariables) {
		    	if(evalNodeFormula == null) {
		    		evalNodeFormula = bmgr.or(formula);
		    	} else {
		    		evalNodeFormula = bmgr.or(formula, evalNodeFormula);
		    	}
		    }
		    try {
				optProver.addConstraint(bmgr.equivalence(bmgr.makeTrue(), evalNodeFormula));
			} catch (InterruptedException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
		   
		    
		    // TODO remove null and make for loop analog
		    a = null;
		    		

		    // Maximize sum of weights
		    int handle = optProver.maximize(imgr.sum(optWeights));

		    try {
				OptStatus response = optProver.check();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		   

		    // for integer theory we get the optimal solution directly as model.
		    // ideal solution: sum=32 with e.g. x=0,y=6,z=4  or  x=0,y=7,z=3  or  x=0,y=8,z=2 ...
		    // getModelAssignments returns a list with [y:10, z:0, x:0]
		    int mergeCount = optProver.upper(handle, Rational.ZERO).get().intValue();
		    System.out.println("maximal sum " + optProver.upper(handle, Rational.ZERO).get() + "with model" + optProver.getModel());
		    System.out.println(optProver.getModelAssignments());
		    
		    ImmutableList<ValueAssignment> valueAssignment = optProver.getModelAssignments();
			//ArrayList<String> variableNames = new ArrayList<String>();
			//list.forEach(assignment -> variableNames.add(assignment.getName())); 
		   
	    	for(ValueAssignment b: valueAssignment) {
				// Search for the evaluation number
	    		String variableName = b.getName();
				if(variableName.matches("\\d*Evaluation")) {
				variableName = variableName.replaceAll("[^0-9]", "");
				boolean satisfied = (boolean) b.getValue();
					if(satisfied) {
						mergeCandidates.add(var2EvalMap.get(Integer.valueOf(variableName)));
					}
				}
			}
					
		    /*
		     * 	Now we have the assignment for each node, we need to return the evaluation with the corresponding generated assignment 
		     * 	We also need to remove 
		     */
		} catch (SolverException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}	
		return mergeCandidates;
	}

	private Set<NodeEvaluation> extractEnabledEvaluations(Map<Integer, NodeEvaluation> var2EvalMap, int[] model) {
		Set<NodeEvaluation> toMerge = new HashSet<>();
		for (int i = 0; i < model.length; i++) {
			// -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
			int var = model[i];
			if (var <= 0) {
				continue;
			}
			NodeEvaluation eval = var2EvalMap.get(var);
			if (eval != null) {
				toMerge.add(eval);
			}
		}
		return toMerge;
	}

	private Map<Integer, NodeEvaluation> pushEvaluations(Set<NodeEvaluation> evaluations) throws ContradictionException {
		Map<Integer, NodeEvaluation> var2EvalMap = new HashMap<>();

		int nextVar = 1;
		for (NodeEvaluation evaluation : evaluations) {
			List<BooleanFormula> evaluationFormulas = new ArrayList<BooleanFormula>();
			int varForEval = getAdditionalVar(nextVar);
			var2EvalMap.put(varForEval, evaluation);
			nextVar++;
			for (IModelNode node : nodes) {
				int varForNode = getVarForNode(node);
				TaggedBoolean value = evaluation.get(node);
				if (value != null) {
					if (value.value) {
						evaluationFormulas.add(bmgr.equivalence((BooleanFormula) nodeMap.get(node), bmgr.makeTrue()));
					} else {
						evaluationFormulas.add(bmgr.equivalence((BooleanFormula) nodeMap.get(node), bmgr.makeFalse()));
					}
				}
			}
			mergeCanditatesList.put(varForEval, evaluationFormulas);
		}
		
		return var2EvalMap;
	}

	private int getAdditionalVar(int i) {
		return nodes.size() + i;
	}

	private IVecInt getVectorForVariables(int... vars) {
		// -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
		IVecInt vector = new VecInt(vars.length + 1);
		for (int i = 0; i < vars.length; i++)
			vector.push(vars[i]);
		return vector;
	}

	private NodeEvaluation mergeAllEvaluations(Set<NodeEvaluation> clique) {
		NodeEvaluation evaluation = new NodeEvaluation();
		for (NodeEvaluation toMerge : clique) {
			evaluation.putAll(toMerge);
		}
		return evaluation;
	}

	/** Fills out all unset nodes in the given node evaluation */
	private NodeEvaluation fill(NodeEvaluation evaluation) throws SpecmateException {
		// return context instead of solver
		ArrayList<Integer> modelList = new ArrayList<Integer> ();
		
		ISolver solver = initSolver(evaluation);
		
		NodeEvaluation filled = new NodeEvaluation();
		
		
		try (ProverEnvironment prover = context.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
			
			
			// Add constraints here
			for(BooleanFormula formula: booleanList) {
				prover.addConstraint(formula);
			}
			
			booleanList.clear();
			
			for(IModelNode node: nodeToMeaningMap.keySet()) {
				prover.addConstraint((BooleanFormula) nodeToMeaningMap.get(node));
			} 
			
			nodeToMeaningMap.clear();
			//prover.addConstraint(booleanList.get(1));
			
			
			boolean isUnsat = prover.isUnsat();
			if (!isUnsat) {
				Model model = prover.getModel();
				ImmutableList<ValueAssignment> list = prover.getModelAssignments();
				//ArrayList<String> variableNames = new ArrayList<String>();
				//list.forEach(assignment -> variableNames.add(assignment.getName())); 
				System.out.println(list.toString());
				
				for(ValueAssignment b: list) {
					// Is the entry a node variable
					if(b.getName().matches("[0-9]")) {
						

						if((boolean) b.getValue()) {
							// Is the variable true add the name with positive sign 
							modelList.add(Integer.valueOf(b.getName()));
						} else {
							// Is the variable false add the name with negative sign
							modelList.add(-Integer.valueOf(b.getName()));
						}
					}
				}
				
			} else {
				/*
				 * TODO: When an evaluation is not satisfiable, we need to handle this case differently 
				 */
				System.out.println("Unsat");
			}
		} catch (Exception e) {
			System.err.println(e);
			System.out.println("Exception2");
		} 
		
		for (int v : modelList) {
			setModelValue(evaluation, filled, v);
		}
		
		return filled;
	}

	/**
	 * Sets the value in an evaluation based on an original evaluation and a model
	 * value.
	 */
	
	private void setModelValue(NodeEvaluation originalEvaluation, NodeEvaluation targetEvaluation, int varNameValue) {
		boolean value = varNameValue > 0;
		int varName = (value ? 1 : -1) * varNameValue;
		IModelNode node = getNodeForVar(varName);
		TaggedBoolean originalValue = originalEvaluation.get(node);
		if (originalValue != null) {
			targetEvaluation.put(node, originalValue);
		} else {
			targetEvaluation.put(node, new TaggedBoolean(value, ETag.AUTO));
		}
		
		System.out.println("Target Evaluation: " + targetEvaluation.toString());
	}

	/** Initializes the SAT4J solver. */
	private GateTranslator initSolver(NodeEvaluation evaluation) throws SpecmateException {
		GateTranslator translator = new GateTranslator(SolverFactory.newLight());
		
		try {
			pushCEGStructure();
			pushEvaluation(evaluation);
		} catch (ContradictionException e) {
			throw new SpecmateException(e);
		}
		return translator;
	}

	// TODO: For each node in every evaluation we need to set the boolean value (this is what usually happens) but also the corresponding Integer Formula
	// We need to check for each node if it is a boolean or if there is also an Integer value behind it --> Future Work 
	private void pushEvaluation(NodeEvaluation evaluation) throws ContradictionException {
		for (IModelNode node : nodes) {
			int varForNode = getVarForNode(node);
			TaggedBoolean value = evaluation.get(node);
			if (value != null) {
				if (value.value) {
					BooleanFormula nodeEqualsTrue = bmgr.equivalence(getBoolVarForCEG(node), bmgr.makeTrue());
					booleanList.add(nodeEqualsTrue);
				} else {
					BooleanFormula nodeEqualsFalse = bmgr.equivalence(getBoolVarForCEG(node), bmgr.makeFalse());
					booleanList.add(nodeEqualsFalse);
				}
			}
		}
	}

	// Construct logic from CEG and add it to the translator
	private void pushCEGStructure() throws ContradictionException {

		for (IModelNode node : nodes) {
			
			BooleanFormula boolForNode = getBoolVarForCEG(node);
			ArrayList<BooleanFormula> boolList = getPredecessorBooleanList(node);
			if (!boolList.isEmpty()) {
				if (((CEGNode) node).getType() == NodeType.AND) {
					BooleanFormula and = bmgr.equivalence(boolForNode, bmgr.and(boolList));  
					booleanList.add(and);
				} else {
					BooleanFormula or = bmgr.equivalence(boolForNode, bmgr.or(boolList));  
					booleanList.add(or);
				}
			}
		}
		for (String text: nodeVariables.keySet()){

            String value = nodeVariables.get(text).toString();  
            System.out.println(text + " " + value);  
		} 
		for(BooleanFormula f: booleanList) {
			System.out.println("BooleanFormula: " + f.toString());
		}
		
		booleanListForZ3 = booleanList;
		
		for(IModelNode mapNode: nodeMap.keySet()) {
			int index = nodes.indexOf(mapNode) + 1; 
			nodeToMeaningMap.put(mapNode, bmgr.equivalence(nodeVariables.get(Integer.toString(index)), (BooleanFormula) nodeMap.get(mapNode)));
		}
		for (IModelNode node: nodeToMeaningMap.keySet()){

            String value = nodeToMeaningMap.get(node).toString();  
            System.out.println("nodeToMeaningMap:" + node + " " + value);  
		} 
		nodeToMeaningMapForZ3 = nodeToMeaningMap;
	}
		
	
	/** Returns the CEG node for a given variable (given as int) */
	private IModelNode getNodeForVar(int i) {
		return nodes.get(i - 1);
	}

	/** Returns a variable (usable for SAT4J) for a given CEG node. */
	private int getVarForNode(IModelNode node) {
		return nodes.indexOf(node) + 1;
	}
	
	private void initSMTSolver() {
		try {
			context = SolverContextFactory.createSolverContext(Solvers.Z3);
		} catch (Exception e) {
			System.out.println("Exception while instantiating Solver Context");
		}
		
		fmgr = context.getFormulaManager();
		

		bmgr = fmgr.getBooleanFormulaManager();
		imgr = fmgr.getIntegerFormulaManager();
		rmgr = fmgr.getRationalFormulaManager();
	}
	
	// check Model 
	// TODO: create own FormulaClass to classify the nodes from the CEG
	// @return: Map where you can find formulas for each node
	
	private HashMap<IModelNode, Formula> classifyCEG() {
		HashMap<IModelNode, Formula> map = new HashMap<>();
		
		for (IModelNode node: nodes) {
			// for each node in the CEG check if it is a boolean formula, Integer formula, Floating Point Formula or a simple assignment (e.g. Getränk = Cola) 
			CEGNode node1 = (CEGNode) node;
			String condition = node1.getCondition();
			String description = node1.getVariable();
			
			// TODO: Unnecessary 
			String nodeDescription = description + condition;
			
			
			// TODO: replace with switch statement 
			
			
			/*
			 *  Possible assiginments
			 *  	--> Alter = 17; Alter = 17.0; Alter<17 usw
			 *  
			 *  	--> Getränk Cola (ohne =) 
			 *  
			 *  	--> Getränk is present (standard belegung) 
			 *  
			 *  	--> Getränk true
			 * 
			 * */
			
			// Integer/Floating Point with equal sign e.g Alter = 17.0
			if (nodeDescription.matches(".*\\s*=\\s*\\d*(\\.)*\\d*")) {
				map.put(node, rmgr.equal(getRatVarForNode(node1), rmgr.makeNumber(Double.valueOf(condition.replaceAll("[^0-9.]", "")))));
			} else if (nodeDescription.matches(".*\\s*≤\\s*\\d*(\\.)*\\d*")) {
				map.put(node, rmgr.lessOrEquals(getRatVarForNode(node1), rmgr.makeNumber(Double.valueOf(condition.replaceAll("[^0-9.]", "")))));
			} else if (nodeDescription.matches(".*\\s*<\\s*\\d*(\\.)*\\d*")) {
				map.put(node, rmgr.lessThan(getRatVarForNode(node1), rmgr.makeNumber(Double.valueOf(condition.replaceAll("[^0-9.]", "")))));
			} else if (nodeDescription.matches(".*\\s*>\\s*\\d*(\\.)*\\d*")) {
				map.put(node, rmgr.greaterThan(getRatVarForNode(node1), rmgr.makeNumber(Double.valueOf(condition.replaceAll("[^0-9.]", "")))));
			} else if (nodeDescription.matches(".*\\s*≥\\s*\\d*(\\.)*\\d*")) {
				map.put(node, rmgr.greaterOrEquals(getRatVarForNode(node1), rmgr.makeNumber(Double.valueOf(condition.replaceAll("[^0-9.]", "")))));
			} else if (nodeDescription.matches(".*is present") || nodeDescription.matches(".*(is)*\\s*(t|T)rue")) {		
				map.put(node, bmgr.equivalence(bmgr.makeTrue(), getBoolVarForNode(node)));	
			} else if (nodeDescription.matches(".*(is)*\\s*(f|F)alse")) {
				map.put(node, bmgr.equivalence(bmgr.makeFalse(), getBoolVarForNode(node)));	
			} else {
				// Nothing from the above cases holds, we assume that it is simple assignment of the form 'Variable' = 'property'
				map.put(node, bmgr.equivalence(getBoolVarForNode(node), bmgr.makeVariable(node1.getCondition())));
			}
		}
		
		for (IModelNode node: map.keySet()){

            String key = node.toString();
            String value = map.get(node).toString();  
            System.out.println(key + " " + value);  
		} 
		
		nodeMap = map;
		
		return map;
	}
	

	// TODO: MAKE Methods generic!!!!!
	
	
	/** Returns a boolean variable (usable for JavaSMT) for a given CEG node. */
	private BooleanFormula getBoolVarForNode(IModelNode node) {
		CEGNode nodeCEG = (CEGNode) node;
		String nodeVar = nodeCEG.getVariable();
		if (!booleanVariables.keySet().contains(nodeVar)) {
			booleanVariables.put(nodeVar, bmgr.makeVariable(nodeVar));
		} 
		return booleanVariables.get(nodeVar);
	}
	
	private BooleanFormula getBoolVarForCEG(IModelNode node) {
		String index = Integer.toString(nodes.indexOf(node) + 1);
 		if (!nodeVariables.keySet().contains(index)) {
 			nodeVariables.put(index, bmgr.makeVariable(index));
 		} 
 		return nodeVariables.get(index);
	}
	
	/** Returns an integer variable (usable for JavaSMT) for a given CEG node. */
	private RationalFormula getRatVarForNode(IModelNode node) {
		CEGNode nodeCEG = (CEGNode) node;
		String nodeVar = nodeCEG.getVariable();
		if (!rationalVariables.keySet().contains(nodeVar)) {
			rationalVariables.put(nodeVar, rmgr.makeVariable(nodeVar));
		} 
		return rationalVariables.get(nodeVar);
	}
	
	/** Returns a variable/value vector for all predeccessors of a node */
	private IVecInt getPredecessorVector(IModelNode node) {
		IVecInt vector = new VecInt();
		for (IModelConnection conn : node.getIncomingConnections()) {
			IModelNode pre = conn.getSource();
			int var = getVarForNode((CEGNode) pre);
			if (((CEGConnection) conn).isNegate()) {
				var *= -1;
			}
			vector.push(var);
		}
		return vector;
	}
	
	/** Returns a variable/value list for all predeccessors of a node */
	private ArrayList<BooleanFormula> getPredecessorBooleanList(IModelNode node) {
		ArrayList<BooleanFormula> list = new ArrayList<BooleanFormula>();
		for (IModelConnection conn : node.getIncomingConnections()) {
			IModelNode pre = conn.getSource();
			int var = getVarForNode((CEGNode) pre);
			BooleanFormula boolNode = bmgr.makeVariable(Integer.toString(var));
			if (((CEGConnection) conn).isNegate()) {
				boolNode = bmgr.not(boolNode);
			}
			list.add(boolNode);
		}
		return list;
	}

	/**
	 * Equality checker that ignores differences in the fields id, name and position
	 */
	private class IdNamePositionIgnoreEqualityHelper extends EqualityHelper {
		@Override
		protected boolean haveEqualFeature(EObject eObject1, EObject eObject2, EStructuralFeature feature) {
			if (feature == BasePackage.Literals.IID__ID) {
				return true;
			}
			if (feature == BasePackage.Literals.INAMED__NAME) {
				return true;
			}
			if (feature == BasePackage.Literals.IPOSITIONABLE__POSITION) {
				return true;
			}
			return super.haveEqualFeature(eObject1, eObject2, feature);
		}
	}

}
