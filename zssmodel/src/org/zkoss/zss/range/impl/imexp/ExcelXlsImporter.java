/*

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		2013/12/01 , Created by Hawk
}}IS_NOTE

Copyright (C) 2013 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
}}IS_RIGHT
*/
package org.zkoss.zss.range.impl.imexp;

import java.io.*;
import java.util.*;

import org.zkoss.poi.hssf.model.HSSFFormulaParser;
import org.zkoss.poi.hssf.record.chart.*;
import org.zkoss.poi.hssf.usermodel.*;
import org.zkoss.poi.hssf.usermodel.HSSFChart.HSSFSeries;
import org.zkoss.poi.ss.usermodel.*;
import org.zkoss.zss.model.*;
import org.zkoss.zss.model.SChart.ChartLegendPosition;
import org.zkoss.zss.model.SChart.ChartType;
import org.zkoss.zss.model.chart.*;

/**
 * 
 * @author Hawk
 * @since 3.5.0
 */
public class ExcelXlsImporter extends AbstractExcelImporter{

	
	@Override
	protected Workbook createPoiBook(InputStream is) throws IOException{
		return new HSSFWorkbook(is);
	}

	@Override
	protected void importExternalBookLinks() {
		// do nothing
		// xls file has no individual external book links
		// every reference has every necessary information including external book index
		// and already resolved by POI
	}
	
	/**
	 * 
	 * @param poiSheet
	 * @return 256
	 */
	private int getLastChangedColumnIndex(Sheet poiSheet) {
		return new HSSFSheetHelper((HSSFSheet)poiSheet).getInternalSheet().getMaxConfiguredColumn();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void importColumn(Sheet poiSheet, SSheet sheet) {
		int lastChangedColumnIndex = getLastChangedColumnIndex(poiSheet);
		for (int c=0 ; c <= lastChangedColumnIndex ; c++){
			//reference Spreadsheet.updateColWidth()
			SColumn col = sheet.getColumn(c);
			int width = ImExpUtils.getWidthAny(poiSheet, c, CHRACTER_WIDTH);
			boolean hidden = poiSheet.isColumnHidden(c);
			col.setHidden(hidden);
			//to avoid creating unnecessary column with just default value
			if(!hidden && width != sheet.getDefaultColumnWidth()){
				col.setWidth(width);
			}
			CellStyle columnStyle = poiSheet.getColumnStyle(c); 
			if (columnStyle != null){
				col.setCellStyle(importCellStyle(columnStyle));
			}
		}
	}

	private void importChart(List<ZssChartX> poiCharts, Sheet poiSheet, SSheet sheet) {
		//reference ChartHelper.drawHSSFChart()
		for (ZssChartX zssChart : poiCharts){
			final HSSFChart hssfChart = (HSSFChart)zssChart.getChartInfo();
			ChartType type = convertChartType(hssfChart);
			SChart chart = null;
			if (type == null){ //ignore unsupported charts
				continue;
			}
			
			chart = sheet.addChart(type, toViewAnchor(poiSheet, zssChart.getPreferredSize()));
			
			switch(type){
				case SCATTER:
					importXySeries(Arrays.asList(hssfChart.getSeries()), (SGeneralChartData)chart.getData());
					break;
				case BUBBLE:
					importXyzSeries(Arrays.asList(hssfChart.getSeries()), (SGeneralChartData)chart.getData());
					break;
				default:
					importSeries(Arrays.asList(hssfChart.getSeries()), (SGeneralChartData)chart.getData());
			}
			
			if (getChartTitle(hssfChart) != null){
				chart.setTitle(hssfChart.getChartTitle());
			}
			chart.setThreeD(hssfChart.getChart3D() != null);
			
			/*
			 * TODO import legend position.
			 * HSSFChart.getLegendPos() always returns a fixed value (7) which doesn't correspond to real legend position. 
			 * According to Excel Binary File Format (.xls) Structure \ 2.4.152 Legend, 
			 * we suspect that LegendRecord's implementation might be incorrect.
			 */
			chart.setLegendPosition(ChartLegendPosition.RIGHT);
//			if (hssfChart.hasLegend()){
//				chart.setLegendPosition(toLengendPosition(hssfChart.getLegendPos()));
//			}
		}

	}
	
	/**
	 * refer to 2.2.3.7 Chart Group
	 * @param hssfChart
	 * @return
	 */
	private ChartType convertChartType(HSSFChart hssfChart){
		if(hssfChart.getType()!=null){
			switch(hssfChart.getType()) {
			case Area:
				return ChartType.AREA;
			case Bar:
				return ((BarRecord)hssfChart.getShapeRecord()).isHorizontal() ? ChartType.BAR : ChartType.COLUMN;
			case Line:
				return ChartType.LINE;
			case Pie:
				return ((PieRecord)hssfChart.getShapeRecord()).getPcDonut() == 0 ? ChartType.PIE: ChartType.DOUGHNUT;
			case Scatter:
				return ((ScatterRecord)hssfChart.getShapeRecord()).isBubbles() ? ChartType.BUBBLE : ChartType.SCATTER;
			}
		}
		return null;
	}
	
	private ChartLegendPosition toLengendPosition(int positionType){
		switch (positionType) {
		case LegendRecord.TYPE_BOTTOM:
			return ChartLegendPosition.BOTTOM;
		case LegendRecord.TYPE_CORNER:
			return ChartLegendPosition.TOP_RIGHT;
		case LegendRecord.TYPE_LEFT:
			return ChartLegendPosition.LEFT;
		case LegendRecord.TYPE_TOP:
			return ChartLegendPosition.TOP;
		case LegendRecord.TYPE_RIGHT:
		case LegendRecord.TYPE_UNDOCKED:
		default:
			return ChartLegendPosition.RIGHT;
		}
	}

	/**
	 * reference DrawingManagerImpl.initHSSFDrawings()
	 * @param poiSheet
	 * @return
	 */
	@Override
	protected void importDrawings(Sheet poiSheet, SSheet sheet) {
		/**
		 * A list of POI chart wrapper loaded during import.
		 */
		List<ZssChartX> poiCharts = new LinkedList<ZssChartX>();
		List<Picture> poiPictures = new LinkedList<Picture>();
		
		//decode drawing/obj/chartStream record into shapes and construct the shape tree in patriarch
		final HSSFPatriarch patriarch = ((HSSFSheet)poiSheet).getDrawingPatriarch(); 
		//will call sheet.getDrawingEscherAggregate() 
		//and try to convert Record to HSSFShapes but will NOT!
		if (patriarch != null) {
			HSSFPatriarchHelper helper = new HSSFPatriarchHelper(patriarch);

			//populate pictures and charts
			for (HSSFShape shape : patriarch.getChildren()) {
				if (shape instanceof HSSFPicture) {
					poiPictures.add((Picture)shape);
				}else if (shape instanceof HSSFChartShape) {
					new HSSFChartDecoder(helper,(HSSFChartShape)shape).decode();
					poiCharts.add((HSSFChartShape)shape);
				} else {
					//log "unprocessed shape"
				}
			}
		}
		importChart(poiCharts, poiSheet, sheet);
		importPicture(poiPictures, poiSheet, sheet);
	}

	/**
	 * reference DefaultBookWidgetLoader.getHSSFHeightInPx()
	 * @param anchor
	 * @param poiSheet
	 * @return
	 */
	@Override
	protected int getAnchorHeightInPx(ClientAnchor anchor, Sheet poiSheet) {
	    final int firstRow = anchor.getRow1();
	    final int firstYoffset = anchor.getDy1();
	    final int firstRowHeight = ImExpUtils.getHeightAny(poiSheet,firstRow);
	    int offsetInFirstRow = (int) Math.round(((double)firstRowHeight) * firstYoffset / 256);
	    final int anchorHeightInFirstRow = firstYoffset >= 256 ? 0 : (firstRowHeight - offsetInFirstRow);  
	    
	    final int lastRow = anchor.getRow2();
	    final int lastRowHeight = ImExpUtils.getHeightAny(poiSheet,lastRow);
	    int anchorHeightInLastRow = (int) Math.round(((double)lastRowHeight) * anchor.getDy2() / 256);  

	    int height = lastRow == firstRow ? anchorHeightInLastRow - offsetInFirstRow : anchorHeightInFirstRow + anchorHeightInLastRow ;
	    //add inter-rows height
	    for (int row = firstRow+1; row < lastRow; ++row) {
	    	height += ImExpUtils.getHeightAny(poiSheet,row);
	    }
	    
	    return height;
	}
	
	/**
	 * reference DefaultBookWidgetLoader.getHSSFWidthInPx()
	 * @param anchor
	 * @param sheet
	 * @return
	 */
	@Override
	protected int getAnchorWidthInPx(ClientAnchor anchor, Sheet sheet) {
	    final int firstColumn = anchor.getCol1();
	    final int firstXoffset = anchor.getDx1();
	    final int firstColumnWidthPixel = ImExpUtils.getWidthAny(sheet,firstColumn, CHRACTER_WIDTH);
	    int offsetInFirstColumn = (int) Math.round(((double)firstColumnWidthPixel) * firstXoffset / 1024);
	    final int anchorWidthInFirstColumn = firstXoffset >= 1024 ? 0 : (firstColumnWidthPixel - offsetInFirstColumn);  
	    
	    final int lastColumn = anchor.getCol2();
	    final int lastColumnWidth = ImExpUtils.getWidthAny(sheet,lastColumn, CHRACTER_WIDTH);
	    int anchorWidthInLastColumn = (int) Math.round(((double)lastColumnWidth ) * anchor.getDx2() / 1024);  
	    
	    int width = firstColumn == lastColumn ? anchorWidthInLastColumn - offsetInFirstColumn : anchorWidthInFirstColumn + anchorWidthInLastColumn;
	    
	    // add inter-column width
	    for (int col = firstColumn+1; col < lastColumn; col++) {
	    	width += ImExpUtils.getWidthAny(sheet,col, CHRACTER_WIDTH);
	    }

	    return width;
	}

	/**
	 * reference ChartHelper.prepareCategoryModel()
	 * @param seriesList
	 * @param chartData
	 */
	private void importSeries(List<HSSFSeries> seriesList, SGeneralChartData chartData) {
		HSSFSeries firstSeries = null;
		if ((firstSeries = seriesList.get(0))!=null){
			chartData.setCategoriesFormula(getCategoryFormula(firstSeries.getDataCategoryLabels()));
		}
		for (int i =0 ;  i< seriesList.size() ; i++){
			HSSFSeries sourceSeries = seriesList.get(i);
			String nameExpression = getTitleFormula(sourceSeries, i);			
			String xValueExpression = getValueFormula(sourceSeries.getDataValues());
			SSeries series = chartData.addSeries();
			series.setFormula(nameExpression, xValueExpression);
		}
	}
	
	/**
	 * reference ChartHelper.prepareXYModel()
	 * @param seriesList
	 * @param chartData
	 */
	private void importXySeries(List<HSSFSeries> seriesList, SGeneralChartData chartData) {
		for (int i =0 ;  i< seriesList.size() ; i++){
			HSSFSeries sourceSeries = seriesList.get(i);
			String nameExpression = getTitleFormula(sourceSeries, i);		
			String xValueExpression = getValueFormula(sourceSeries.getDataCategoryLabels());
			String yValueExpression = getValueFormula(sourceSeries.getDataValues());
			SSeries series = chartData.addSeries();
			series.setXYFormula(nameExpression, xValueExpression, yValueExpression);
		}
	}
	
	private void importXyzSeries(List<HSSFSeries> seriesList, SGeneralChartData chartData) {
		for (int i =0 ;  i< seriesList.size() ; i++){
			HSSFSeries sourceSeries = seriesList.get(i);
			String nameExpression = getTitleFormula(sourceSeries, i);		
			String xValueExpression = getValueFormula(sourceSeries.getDataCategoryLabels());
			String yValueExpression = getValueFormula(sourceSeries.getDataValues());
			String zValueExpression = getValueFormula(sourceSeries.getDataSecondaryCategoryLabels());
			SSeries series = chartData.addSeries();
			series.setXYZFormula(nameExpression, xValueExpression, yValueExpression, zValueExpression);
		}
	}


	/**
	 * cannot import string literal value.
	 * @param dataValues
	 * @return
	 */
	private String getValueFormula(LinkedDataRecord dataValues) {
		if (dataValues.getReferenceType() == LinkedDataRecord.REFERENCE_TYPE_WORKSHEET) {
			return HSSFFormulaParser.toFormulaString((HSSFWorkbook)workbook, dataValues.getFormulaOfLink());
		}else{
			return "0";
		}
	}

	/**
	 * cannot import string literal value.
	 * @param dataCategoryLabels
	 * @return
	 */
	private String getCategoryFormula(LinkedDataRecord dataCategoryLabels) {
		if (dataCategoryLabels.getReferenceType() == LinkedDataRecord.REFERENCE_TYPE_WORKSHEET) {
			return HSSFFormulaParser.toFormulaString((HSSFWorkbook)workbook, dataCategoryLabels.getFormulaOfLink());
		}else{
			return "";
		}
	}
	
	private String getTitleFormula(HSSFSeries series, int index) {
		if (series.getDataName().getReferenceType() == LinkedDataRecord.REFERENCE_TYPE_WORKSHEET){
			return HSSFFormulaParser.toFormulaString((HSSFWorkbook)workbook, series.getDataName().getFormulaOfLink());
		}

		return series.getSeriesTitle() == null ? "\"Series"+index+"\"" : "\""+series.getSeriesTitle()+"\"";
	}
	
	private String getChartTitle(HSSFChart hssfChart) {
		if (!hssfChart.isAutoTitleDeleted()) {
			return hssfChart.getChartTitle();
		}
		return null;
	}

	@Override
	protected int getXoffsetInPixel(ClientAnchor anchor, Sheet poiSheet) {
	    final int firstColumn = anchor.getCol1();
	    final int firstXoffset = anchor.getDx1();
	    
	    final int columnWidthPixel = ImExpUtils.getWidthAny(poiSheet,firstColumn, CHRACTER_WIDTH);
	    return firstXoffset >= 1024 ? columnWidthPixel : (int) Math.round(((double)columnWidthPixel) * firstXoffset / 1024);  
	}
	
	@Override
	protected int getYoffsetInPixel(ClientAnchor anchor, Sheet poiSheet) {
		final int firstRow = anchor.getRow1();
		final int firstYoffset = anchor.getDy1();

		final int rowHeightPixel = ImExpUtils.getHeightAny(poiSheet,firstRow);
		return firstYoffset >= 256 ? rowHeightPixel : (int) Math.round(((double)rowHeightPixel) * firstYoffset / 256);  
	}

	@Override
	protected void importValidation(Sheet poiSheet, SSheet sheet) {
		// unsupported features
	}
}