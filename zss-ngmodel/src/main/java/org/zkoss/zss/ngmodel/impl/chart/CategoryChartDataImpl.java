package org.zkoss.zss.ngmodel.impl.chart;

import java.util.LinkedList;
import java.util.List;

import org.zkoss.zss.ngmodel.ErrorValue;
import org.zkoss.zss.ngmodel.InvalidateModelOpException;
import org.zkoss.zss.ngmodel.NChart;
import org.zkoss.zss.ngmodel.chart.NCategoryChartData;
import org.zkoss.zss.ngmodel.chart.NSeries;
import org.zkoss.zss.ngmodel.impl.BookSeriesAdv;
import org.zkoss.zss.ngmodel.impl.ChartAdv;
import org.zkoss.zss.ngmodel.impl.LinkedModelObject;
import org.zkoss.zss.ngmodel.impl.RefImpl;
import org.zkoss.zss.ngmodel.sys.EngineFactory;
import org.zkoss.zss.ngmodel.sys.dependency.Ref;
import org.zkoss.zss.ngmodel.sys.formula.EvaluationResult;
import org.zkoss.zss.ngmodel.sys.formula.EvaluationResult.ResultType;
import org.zkoss.zss.ngmodel.sys.formula.FormulaEngine;
import org.zkoss.zss.ngmodel.sys.formula.FormulaEvaluationContext;
import org.zkoss.zss.ngmodel.sys.formula.FormulaExpression;
import org.zkoss.zss.ngmodel.sys.formula.FormulaParseContext;

public class CategoryChartDataImpl extends ChartDataAdv implements NCategoryChartData{

	private static final long serialVersionUID = 1L;

	private FormulaExpression catFormula;
	
	final private List<SeriesImpl> serieses = new LinkedList<SeriesImpl>();
	private ChartAdv chart;
	final private String id;
	
	private Object evalResult;
	
	private boolean evaluated = false;
	
	private int seriesCount = 0;
	
	public CategoryChartDataImpl(ChartAdv chart,String id){
		this.chart = chart;
		this.id = id;
	}
	
	public NChart getChart(){
		return chart;
	}
	
	/*package*/ void evalFormula(){
		if(!evaluated){
			if(catFormula!=null){
				FormulaEngine fe = EngineFactory.getInstance().createFormulaEngine();
				EvaluationResult result = fe.evaluate(catFormula,new FormulaEvaluationContext(chart.getSheet().getBook()));
				Object val = result.getValue();
				if(result.getType() == ResultType.SUCCESS){
					evalResult = val;
				}else if(result.getType() == ResultType.ERROR){
					evalResult = (val instanceof ErrorValue)?val:new ErrorValue(ErrorValue.INVALID_NAME);
				}
			}
			//TODO
			evaluated = true;
		}
	}
	
	public int getNumOfSeries() {
		return serieses.size();
	}
	public NSeries getSeries(int i) {
		return serieses.get(i);
	}
	public int getNumOfCategory() {
		evalFormula();
		return ChartDataUtil.sizeOf(evalResult);
	}
	public Object getCategory(int i) {
		evalFormula();
		if(i>=ChartDataUtil.sizeOf(evalResult)){
			return null;
		}
		return ChartDataUtil.valueOf(evalResult,i);
	}
	public NSeries addSeries() {
		SeriesImpl series = new SeriesImpl(chart,id + (seriesCount++));
		serieses.add(series);
		return series;
	}
	
	protected void checkOwnership(NSeries series){
		if(!serieses.contains(series)){
			throw new InvalidateModelOpException("doesn't has ownership "+ series);
		}
	}
	
	public void removeSeries(NSeries series) {
		checkOwnership(series);
		((SeriesImpl)series).release();
		serieses.remove(series);
		
	}
	public void setCategoriesFormula(String expr) {
		checkOrphan();
		evaluated = false;
		
		clearFormulaDependency();
		
		//TODO dependency tracking on chart
		FormulaEngine fe = EngineFactory.getInstance().createFormulaEngine();
		catFormula = fe.parse(expr, new FormulaParseContext(chart.getSheet().getBook()));
	}

	@Override
	public String getCategoriesFormula() {
		return catFormula==null?null:catFormula.getFormulaString();
	}

	@Override
	public void clearFormulaResultCache() {
		evalResult = null;
		evaluated = false;
		for(SeriesImpl series:serieses){
			series.clearFormulaResultCache();
		}
	}
	
	private void clearFormulaDependency(){
		if(catFormula!=null){
			Ref ref = new RefImpl(chart,id);
			((BookSeriesAdv) chart.getSheet().getBook().getBookSeries())
					.getDependencyTable().clearDependents(ref);
		}
	}

	@Override
	public void release() {
		checkOrphan();
		clearFormulaDependency();
		for(SeriesImpl series:serieses){
			series.release();
		}
		chart = null;
	}

	@Override
	public void checkOrphan() {
		if(chart==null){
			throw new IllegalStateException("doesn't connect to parent");
		}
	}
}
