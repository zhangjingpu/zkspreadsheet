/*

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		2013/12/01 , Created by dennis
}}IS_NOTE

Copyright (C) 2013 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
}}IS_RIGHT
*/
package org.zkoss.zss.range.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;

import org.zkoss.poi.ss.usermodel.ZssContext;
import org.zkoss.poi.ss.util.WorkbookUtil;
import org.zkoss.lang.Strings;
import org.zkoss.util.Locales;
import org.zkoss.zss.model.CellRegion;
import org.zkoss.zss.model.InvalidModelOpException;
import org.zkoss.zss.model.PasteOption;
import org.zkoss.zss.model.SAutoFilter;
import org.zkoss.zss.model.SAutoFilter.FilterOp;
import org.zkoss.zss.model.SBook;
import org.zkoss.zss.model.SBookSeries;
import org.zkoss.zss.model.SCell;
import org.zkoss.zss.model.SCell.CellType;
import org.zkoss.zss.model.SCellStyle;
import org.zkoss.zss.model.SCellStyle.BorderType;
import org.zkoss.zss.model.SChart;
import org.zkoss.zss.model.SChart.ChartGrouping;
import org.zkoss.zss.model.SChart.ChartLegendPosition;
import org.zkoss.zss.model.SChart.ChartType;
import org.zkoss.zss.model.SColumn;
import org.zkoss.zss.model.SDataValidation;
import org.zkoss.zss.model.SDataValidation.AlertStyle;
import org.zkoss.zss.model.SDataValidation.OperatorType;
import org.zkoss.zss.model.SDataValidation.ValidationType;
import org.zkoss.zss.model.SHyperlink;
import org.zkoss.zss.model.SHyperlink.HyperlinkType;
import org.zkoss.zss.model.SPicture;
import org.zkoss.zss.model.SRow;
import org.zkoss.zss.model.SSheet;
import org.zkoss.zss.model.SSheetProtection;
import org.zkoss.zss.model.SSheetViewInfo;
import org.zkoss.zss.model.SheetRegion;
import org.zkoss.zss.model.SName;
import org.zkoss.zss.model.ViewAnchor;
import org.zkoss.zss.model.ErrorValue;
import org.zkoss.zss.model.impl.AbstractBookSeriesAdv;
import org.zkoss.zss.model.impl.AbstractSheetAdv;
import org.zkoss.zss.model.impl.AbstractNameAdv;
import org.zkoss.zss.model.impl.AbstractCellAdv;
import org.zkoss.zss.model.impl.DataValidationImpl;
import org.zkoss.zss.model.impl.FormulaCacheCleaner;
import org.zkoss.zss.model.impl.RefImpl;
import org.zkoss.zss.model.impl.NameRefImpl;
import org.zkoss.zss.model.impl.AbstractDataValidationAdv;
import org.zkoss.zss.model.sys.EngineFactory;
import org.zkoss.zss.model.sys.dependency.DependencyTable;
import org.zkoss.zss.model.sys.dependency.DependencyTable.RefFilter;
import org.zkoss.zss.model.sys.dependency.NameRef;
import org.zkoss.zss.model.sys.dependency.Ref;
import org.zkoss.zss.model.sys.dependency.Ref.RefType;
import org.zkoss.zss.model.sys.format.FormatContext;
import org.zkoss.zss.model.sys.format.FormatEngine;
import org.zkoss.zss.model.sys.input.InputEngine;
import org.zkoss.zss.model.sys.input.InputParseContext;
import org.zkoss.zss.model.sys.input.InputResult;
import org.zkoss.zss.model.util.ReadWriteTask;
import org.zkoss.zss.model.util.Validations;
import org.zkoss.zss.range.SRange;
import org.zkoss.zss.range.SRanges;
import org.zkoss.zss.range.impl.autofill.AutoFillHelper;
/**
 * Only those methods that set cell data, cell style, row (column) style, width, height, and hidden consider 3-D references. 
 * Others don't, just perform on first cell.
 * @author dennis
 * @since 3.5.0
 */
public class RangeImpl implements SRange {

	private SBook _book;
	private final List<EffectedRegion> _rangeRefs = new ArrayList<EffectedRegion>(
			1);

	private int _column = Integer.MAX_VALUE;
	private int _row = Integer.MAX_VALUE;
	private int _lastColumn = Integer.MIN_VALUE;
	private int _lastRow = Integer.MIN_VALUE;

	public RangeImpl(SBook book) {
		this._book = book;
	}
	
	public RangeImpl(SSheet sheet) {
		addRangeRef(sheet, 0, 0, sheet.getBook().getMaxRowIndex(), sheet
				.getBook().getMaxColumnIndex());
	}

	public RangeImpl(SSheet sheet, int row, int col) {
		addRangeRef(sheet, row, col, row, col);
	}

	public RangeImpl(SSheet sheet, int tRow, int lCol, int bRow, int rCol) {
		addRangeRef(sheet, tRow, lCol, bRow, rCol);
	}
	
	public RangeImpl(SSheet sheet, CellRegion region) {
		addRangeRef(sheet, region.getRow(), region.getColumn(), region.getLastRow(), region.getLastColumn());
	}
	
	private RangeImpl(Collection<SheetRegion> regions) {
		for(SheetRegion region:regions){
			addRangeRef(region.getSheet(), region.getRow(), region.getColumn(), region.getLastRow(), region.getLastColumn());
		}
	}

	private void addRangeRef(SSheet sheet, int tRow, int lCol, int bRow,
			int rCol) {
		Validations.argNotNull(sheet);
		//TODO to support multiple sheet
		_rangeRefs.add(new EffectedRegion(sheet, tRow, lCol, bRow, rCol));

		_column = Math.min(_column, lCol);
		_row = Math.min(_row, tRow);
		_lastColumn = Math.max(_lastColumn, rCol);
		_lastRow = Math.max(_lastRow, bRow);

	}
	
	
	public ReadWriteLock getLock(){
		return getBookSeries().getLock();
	}

	
	private class CellVisitorTask extends ReadWriteTask{
		private CellVisitor visitor;
		private boolean stop = false;
		
		private CellVisitorTask(CellVisitor visitor){
			this.visitor = visitor;
		}

		@Override
		public Object invoke() {
			travelCells(visitor);
			return null;
		}
	}
	
	SBookSeries getBookSeries(){
		return getBook().getBookSeries();
	}
	
	SBook getBook(){
		if(_book==null){
			_book = getSheet().getBook();
		}
		return _book;
	}
	
	@Override
	public SSheet getSheet() {
		if(_rangeRefs.size()<=0){
			throw new IllegalStateException("can't find any effected sheet or range");
		}
		return _rangeRefs.get(0).sheet;
	}
	@Override
	public int getRow() {
		return _row;
	}
	@Override
	public int getColumn() {
		return _column;
	}
	@Override
	public int getLastRow() {
		return _lastRow;
	}
	@Override
	public int getLastColumn() {
		return _lastColumn;
	}

	private class EffectedRegion {
		private final SSheet sheet;
		private final CellRegion region;

		public EffectedRegion(SSheet sheet, int row, int column, int lastRow,
				int lastColumn) {
			this.sheet = sheet;
			region = new CellRegion(row, column,lastRow,lastColumn);
		}
	}

	private abstract class CellVisitor {
		/**
		 * @param cell
		 * @return true if continue the visit next cell
		 */
		abstract boolean visit(SCell cell);
		
		public void afterVisitAll(){}
	}
	
	private abstract class CellVisitorForUpdate extends CellVisitor{
		@Override
		public void afterVisitAll(){
			SBookSeries bookSeries = getSheet().getBook().getBookSeries();
			DependencyTable table = ((AbstractBookSeriesAdv)bookSeries).getDependencyTable();
			for (EffectedRegion r : _rangeRefs) {
				CellRegion region = r.region;
				handleCellNotifyContentChange(new SheetRegion(r.sheet,r.region));
				handleRefNotifyContentChange(bookSeries, table.getEvaluatedDependents(new RefImpl(r.sheet.getBook().getBookName(),r.sheet.getSheetName(),
						region.getRow(),region.getColumn(),region.getLastRow(),region.getLastColumn())));
				
			}
		}
	}
	

	/**
	 * travels all the cells in this range
	 * @param visitor
	 */
	private void travelCells(CellVisitor visitor) {
		//don't use updateWrap's notify, update whole range at once, to prevent update separately
		ModelUpdateWrapper updateWrap = new ModelUpdateWrapper(getBookSeries(),false);
		try{
			for (EffectedRegion r : _rangeRefs) {
				CellRegion region = r.region;
				loop1:
				for (int i = region.row; i <= region.lastRow; i++) {
					for (int j = region.column; j <= region.lastColumn; j++) {
						SCell cell = r.sheet.getCell(i, j);
						boolean conti = visitor.visit(cell);
						if(!conti){
							break loop1;
						}
					}
				}
			}
		}finally{
			updateWrap.doFinially();
		}
		
		//don't use updateWrap's notify, update whole range at once, to prevent update separately
		visitor.afterVisitAll();
	}

	private void handleRefNotifyContentChange(SBookSeries bookSeries,Set<Ref> notifySet) {
		// notify changes
		new RefNotifyContentChangeHelper(bookSeries).notifyContentChange(notifySet);
	}
	private void handleRefNotifyContentChange(SBookSeries bookSeries,Ref notify) {
		// notify changes
		new RefNotifyContentChangeHelper(bookSeries).notifyContentChange(notify);
	}

	private boolean euqlas(Object obj1, Object obj2) {
		if (obj1 == obj2) {
			return true;
		}
		if (obj1 != null) {
			return obj1.equals(obj2);
		}
		return false;
	}
	@Override
	public void setValue(final Object value) {
		new CellVisitorTask(new CellVisitorForUpdate() {
			public boolean visit(SCell cell) {
				Object cellval = cell.getValue();
				if (!euqlas(cellval, value)) {
					cell.setValue(value);
				}
				return true;
			}
		}).doInWriteLock(getLock());
	}
	
	@Override
	public void clearContents() {
		new ModelManipulationTask() {
			@Override
			protected boolean isCollectModelUpdate(){
				//it notify change at once for all the cell in the range
				return false;
			}
			@Override
			protected Object doInvoke() {
				SBookSeries bookSeries = getSheet().getBook().getBookSeries();
				DependencyTable table = ((AbstractBookSeriesAdv)bookSeries).getDependencyTable();
				//clear cells instance directly (not just clear it's data, but it instance directly)
				for (EffectedRegion r : _rangeRefs) {
					SBook book = r.sheet.getBook();
					CellRegion region = r.region;
					new ClearCellHelper(new RangeImpl(r.sheet,r.region)).clearCellContent();
					
					handleCellNotifyContentChange(new SheetRegion(r.sheet,r.region));
					
					boolean wholeSheet = region.row==0 && region.lastRow>=book.getMaxRowIndex() 
							&& region.column==0 && region.lastColumn>=book.getMaxColumnIndex();
					if(!wholeSheet){//no need to notify again if it is whole sheet already
						handleRefNotifyContentChange(bookSeries, table.getEvaluatedDependents(new RefImpl(r.sheet.getBook().getBookName(),r.sheet.getSheetName(),
							region.row,region.column,region.lastRow,region.lastColumn)));
					}
				}
				return null;
			}
		}.doInWriteLock(getLock());
	}
	
	@Override
	public void clearCellStyles() {
		new ModelManipulationTask() {
			@Override
			protected boolean isCollectModelUpdate(){
				//it notify change at once for all the cell in the range
				return false;
			}
			@Override
			protected Object doInvoke() {
				for (EffectedRegion r : _rangeRefs) {
					new ClearCellHelper(new RangeImpl(r.sheet,r.region)).clearCellStyle();
					handleCellNotifyContentChange(new SheetRegion(r.sheet,r.region));
				}
				return null;
			}
		}.doInWriteLock(getLock());
	}	

	@Override
	public void clearAll() {
		new ModelManipulationTask() {
			@Override
			protected boolean isCollectModelUpdate(){
				//it notify change at once for all the cell in the range
				return false;
			}
			@Override
			protected Object doInvoke() {
				SBookSeries bookSeries = getSheet().getBook().getBookSeries();
				DependencyTable table = ((AbstractBookSeriesAdv)bookSeries).getDependencyTable();
				//clear cells instance directly (not just clear it's data, but it instance directly)
				for (EffectedRegion r : _rangeRefs) {
					CellRegion region = r.region;
					r.sheet.clearCell(region);
					
					//we still need to handle the row/column style case.
					new ClearCellHelper(new RangeImpl(r.sheet,r.region)).clearCellStyle();
					
					handleCellNotifyContentChange(new SheetRegion(r.sheet,r.region));
					handleRefNotifyContentChange(bookSeries, table.getEvaluatedDependents(new RefImpl(r.sheet.getBook().getBookName(),r.sheet.getSheetName(),
							region.getRow(),region.getColumn(),region.getLastRow(),region.getLastColumn())));
				}
				return null;
			}
			@Override
			protected void doAfterNotify(){
				//it's clear all, unmerge the cells
				unmerge();
			}
		}.doInWriteLock(getLock());
	}

	static class ResultWrap<T> {
		T obj;
		public ResultWrap(){}
		public ResultWrap(T obj){
			this.obj = obj;
		}
		public T get() {
			return obj;
		}
		public void set(T obj) {
			this.obj = obj;
		}
	}
	
	@Override
	public void setEditText(final String editText) {
		final InputEngine ie = EngineFactory.getInstance().createInputEngine();
		final ResultWrap<InputResult> input = new ResultWrap<InputResult>();
		final ResultWrap<HyperlinkType> hyperlinkType = new ResultWrap<HyperlinkType>();
		new CellVisitorTask(new CellVisitorForUpdate() {
			public boolean visit(SCell cell) {
				//ZSS-565: Support input with Swedish locale into formula
				Locale locale = ZssContext.getCurrent().getLocale();

				InputResult result;
				if((result = input.get())==null){
					result = ie.parseInput(editText == null ? ""
						: editText, cell.getCellStyle().getDataFormat(), new InputParseContext(locale));
					input.set(result);
					
					//check if a hyperlink
					if(result.getType() == CellType.STRING){
						hyperlinkType.set(getHyperlinkType((String)result.getValue()));
					}
				}
				
				Object cellval = cell.getValue();
				Object resultVal = result.getValue();
				
				if (cell.getType()==result.getType() && euqlas(cellval, resultVal)) {
					return true;
				}
				String format = result.getFormat();
				
				switch (result.getType()) {
				case BLANK:
					cell.clearValue();
					break;
				case BOOLEAN:
					cell.setBooleanValue((Boolean) resultVal);
					break;
				case FORMULA:
					((AbstractCellAdv)cell).setFormulaValue((String) resultVal, locale); //ZSS-565
					break;
				case NUMBER:
					if(resultVal instanceof Date){
						cell.setDateValue((Date)resultVal);
					}else{
						cell.setNumberValue((Double) resultVal);
					}
					break;
				case STRING:
					cell.setStringValue((String) resultVal);
					if(hyperlinkType.get()!=null){
						cell.setupHyperlink(hyperlinkType.get(),(String)resultVal,(String)resultVal);
					}
					break;
				case ERROR:
					cell.setErrorValue(ErrorValue.valueOf(((Byte)resultVal).byteValue())); //ZSS-672
					break;
				default:
					cell.setValue(resultVal);
				}
				
				String oldFormat = cell.getCellStyle().getDataFormat();
				if(format!=null && SCellStyle.FORMAT_GENERAL.equals(oldFormat)){
					//if there is a suggested format and old format is not general
					StyleUtil.setDataFormat(cell.getSheet().getBook(), cell, format);
				}
				return true;
			}
		}).doInWriteLock(getLock());
	}
	
	private SHyperlink.HyperlinkType getHyperlinkType(String address) {
		if (address != null) {
			final String addr = address.toLowerCase(); // ZSS-288: support more scheme according to POI code, see  org.zkoss.poi.ss.formula.functions.Hyperlink
			if (addr.startsWith("http://") || addr.startsWith("https://")) {
				return SHyperlink.HyperlinkType.URL;
			} else if (addr.startsWith("mailto:")) {
				return SHyperlink.HyperlinkType.EMAIL;
			} // ZSS-288: don't support auto-create hyperlink for DOCUMENT and FILE type
		}
		return null;
	}

	@Override
	public String getEditText() {
		final ResultWrap<String> r = new ResultWrap<String>();
		new CellVisitorTask(new CellVisitor() {
			@Override
			public boolean visit(SCell cell) {
				FormatEngine fe = EngineFactory.getInstance().createFormatEngine();
				r.set(fe.getEditText(cell, new FormatContext(ZssContext.getCurrent().getLocale())));		
				return false;
			}
		}).doInReadLock(getLock());
		return r.get();
	}

	@Override
	public void notifyChange() {
		new ReadWriteTask(){
			@Override
			public Object invoke() {
				notifyChangeInLock(true);
				return null;
			}
		
		}.doInWriteLock(getLock());//we will clear formula cache, so need to use write lock
	}
	
	private void notifyChangeInLock(boolean notifyDependent){
		SBookSeries bookSeries = getBookSeries();
		DependencyTable table = ((AbstractBookSeriesAdv)bookSeries).getDependencyTable();
		LinkedHashSet<Ref> notifySet = new LinkedHashSet<Ref>();
		FormulaCacheCleaner cacheCleaner = new FormulaCacheCleaner(bookSeries);
		for (EffectedRegion r : _rangeRefs) {
			SBook book = r.sheet.getBook();
			String bookName = book.getBookName();
			String sheetName = r.sheet.getSheetName();
			CellRegion region = r.region;
			Ref pre = new RefImpl(bookName, sheetName, region.row, region.column,region.lastRow,region.lastColumn);
			cacheCleaner.clearByPrecedent(pre);
			notifySet.add(pre);
			boolean wholeSheet = region.row==0 && region.lastRow>=book.getMaxRowIndex() 
					&& region.column==0 && region.lastColumn>=book.getMaxColumnIndex();
			if(notifyDependent && !wholeSheet){
				notifySet.addAll(table.getEvaluatedDependents(pre));
			}
		}
		handleRefNotifyContentChange(bookSeries,notifySet);
	}
	
	public void notifyChange(final String[] variables){
		SBookSeries bookSeries = getBookSeries();
		DependencyTable table = ((AbstractBookSeriesAdv)bookSeries).getDependencyTable();
		LinkedHashSet<Ref> notifySet = new LinkedHashSet<Ref>();
		FormulaCacheCleaner cacheCleaner = new FormulaCacheCleaner(bookSeries);
		new ReadWriteTask(){
			@Override
			public Object invoke() {
				SBookSeries bookSeries = getBookSeries();
				DependencyTable table = ((AbstractBookSeriesAdv)bookSeries).getDependencyTable();
				LinkedHashSet<Ref> notifySet = new LinkedHashSet<Ref>();
				FormulaCacheCleaner cacheCleaner = new FormulaCacheCleaner(bookSeries);
				for (EffectedRegion r : _rangeRefs) {
					final String bookName = r.sheet.getBook().getBookName();
					String sheetName = r.sheet.getSheetName();
					for(final String var:variables){
						Set<Ref> precedents = table.searchPrecedents(new RefFilter() {
							@Override
							public boolean accept(Ref ref) {
								//search name that has var prefix, (we use NAME to achieve var binding)
								if(ref.getBookName().equals(bookName) && ref.getType()==RefType.NAME){
									String refNameName = ((NameRef)ref).getNameName();
									if(refNameName.equals(var) || refNameName.startsWith(var+".")){
										return true;
									}
								}
								return false;
							}
						});
						
						for(Ref pre:precedents){
							cacheCleaner.clearByPrecedent(pre);
							notifySet.add(pre);
							notifySet.addAll(table.getEvaluatedDependents(pre));
						}
						
					}
				}
				handleRefNotifyContentChange(bookSeries,notifySet);
				return null;
			}
		
		}.doInWriteLock(getLock());//we will clear formula cache, so need to use write lock		
		
	}
	
	@Override
	public boolean isWholeSheet(){
		return isWholeRow()&&isWholeColumn();
	}

	@Override
	public boolean isWholeRow() {
		return _column<=0 && _lastColumn>=getBook().getMaxColumnIndex();
	}

	@Override
	public SRange getRows() {
		return new RangeImpl(getSheet(), _row, 0, _lastRow,getBook().getMaxColumnIndex());
	}

	@Override
	public void setRowHeight(final int heightPx) {
		setRowHeight(heightPx,true);
	}
	public void setRowHeight(final int heightPx, final boolean custom) {
		new ReadWriteTask() {
			@Override
			public Object invoke() {
				setRowHeightInLock(heightPx,null,custom);
				return null;
			}
		}.doInWriteLock(getLock());
	}
	private void setRowHeightInLock(Integer heightPx,Boolean hidden, Boolean custom){
		LinkedHashSet<SheetRegion> notifySet = new LinkedHashSet<SheetRegion>();

		for (EffectedRegion r : _rangeRefs) {
			int maxcol = r.sheet.getBook().getMaxColumnIndex();
			CellRegion region = r.region;
			
			for (int i = region.row; i <= region.lastRow; i++) {
				SRow row = r.sheet.getRow(i);
				if(heightPx!=null){
					row.setHeight(heightPx);
				}
				if(hidden!=null){
					row.setHidden(hidden);
				}
				if(custom!=null){
					row.setCustomHeight(custom);
				}
				notifySet.add(new SheetRegion(r.sheet,i,0,i,maxcol));
			}
		}

		new NotifyChangeHelper().notifyRowColumnSizeChange(notifySet);
	}

	@Override
	public boolean isWholeColumn() {
		return _row<=0 && _lastRow>=getBook().getMaxRowIndex();
	}

	@Override
	public SRange getColumns() {
		return new RangeImpl(getSheet(), 0, _column, getBook().getMaxRowIndex(), _lastColumn);
	}

	@Override
	public void setColumnWidth(final int widthPx) {
		setColumnWidth(widthPx,true);
	}
	public void setColumnWidth(final int widthPx,final boolean custom) {
		new ReadWriteTask() {
			@Override
			public Object invoke() {
				setColumnWidthInLock(widthPx,null,custom);
				return null;
			}
		}.doInWriteLock(getLock());
	}
	private void setColumnWidthInLock(Integer widthPx,Boolean hidden, Boolean custom){
		LinkedHashSet<SheetRegion> notifySet = new LinkedHashSet<SheetRegion>();

		for (EffectedRegion r : _rangeRefs) {
			int maxrow = r.sheet.getBook().getMaxRowIndex();
			CellRegion region = r.region;
			
			for (int i = region.column; i <= region.lastColumn; i++) {
				SColumn column = r.sheet.getColumn(i);
				if(widthPx!=null){
					column.setWidth(widthPx);
				}
				if(hidden!=null){
					column.setHidden(hidden);
				}
				if(custom!=null){
					column.setCustomWidth(true);
				}
				notifySet.add(new SheetRegion(r.sheet,0,i,maxrow,i));
			}
		}
		new NotifyChangeHelper().notifyRowColumnSizeChange(notifySet);
		new NotifyChangeHelper().notifyCellChange(notifySet); //ZSS-666
	}

	@Override
	public SHyperlink getHyperlink() {
		final ResultWrap<SHyperlink> r = new ResultWrap<SHyperlink>();
		new CellVisitorTask(new CellVisitor() {
			@Override
			public boolean visit(SCell cell) {
				r.set(cell.getHyperlink());		
				return false;
			}
		}).doInReadLock(getLock());
		return r.get();
	}

	@Override
	public SRange copy(final SRange dstRange, final boolean cut) {
		PasteOption option = new PasteOption();
		option.setCut(cut);
		return pasteSpecial0(dstRange,option);		
	}

	@Override
	public SRange copy(SRange dstRange) {
		return copy(dstRange,false);
	}

	@Override
	public SRange pasteSpecial(SRange dstRange, PasteType pasteType,
			PasteOperation pasteOp, boolean skipBlanks, boolean transpose) {
		PasteOption option = new PasteOption();
		option.setSkipBlank(skipBlanks);
		option.setTranspose(transpose);
		option.setPasteType(toModelPasteType(pasteType));
		option.setPasteOperation(toModelPasteOperation(pasteOp));
		return pasteSpecial0(dstRange,option);
	}
	
	private PasteOption.PasteOperation toModelPasteOperation(
			PasteOperation pasteOp) {
		switch(pasteOp){
		case ADD:
			return PasteOption.PasteOperation.ADD;
		case DIV:
			return PasteOption.PasteOperation.DIV;
		case MUL:
			return PasteOption.PasteOperation.MUL;
		case NONE:
			return PasteOption.PasteOperation.NONE;
		case SUB:
			return PasteOption.PasteOperation.SUB;
		}
		throw new IllegalStateException("unknow operation "+pasteOp);
	}

	private PasteOption.PasteType toModelPasteType(
			PasteType pasteType) {
		switch(pasteType){
		case ALL:
			return PasteOption.PasteType.ALL;
		case ALL_EXCEPT_BORDERS:
			return PasteOption.PasteType.ALL_EXCEPT_BORDERS;
		case COLUMN_WIDTHS:
			return PasteOption.PasteType.COLUMN_WIDTHS;
		case COMMENTS:
			return PasteOption.PasteType.COMMENTS;
		case FORMATS:
			return PasteOption.PasteType.FORMATS;
		case FORMULAS:
			return PasteOption.PasteType.FORMULAS;
		case FORMULAS_AND_NUMBER_FORMATS:
			return PasteOption.PasteType.FORMULAS_AND_NUMBER_FORMATS;
		case VALIDATAION:
			return PasteOption.PasteType.VALIDATAION;
		case VALUES:
			return PasteOption.PasteType.VALUES;
		case VALUES_AND_NUMBER_FORMATS:
			return PasteOption.PasteType.VALUES_AND_NUMBER_FORMATS;
		}
		throw new IllegalStateException("unknow type "+pasteType);
	}

	public SRange pasteSpecial0(final SRange dstRange, final PasteOption option) {
		final ResultWrap<CellRegion> effectedRegion = new ResultWrap<CellRegion>();
		return (SRange)new ModelManipulationTask(){
			@Override
			protected Object doInvoke() {
				CellRegion effected = dstRange.getSheet().pasteCell(new SheetRegion(getSheet(),getRow(),getColumn(),getLastRow(),getLastColumn()), 
						new CellRegion(dstRange.getRow(),dstRange.getColumn(),dstRange.getLastRow(),dstRange.getLastColumn()),
						option);
				effectedRegion.set(effected);
				return new RangeImpl(getSheet(),effected.getRow(),effected.getColumn(),effected.getLastRow(),effected.getLastColumn());
			}
			@Override
			protected void doBeforeNotify() {
				if(option.getPasteType()==PasteOption.PasteType.COLUMN_WIDTHS){
					CellRegion effected = effectedRegion.get();
					new NotifyChangeHelper().notifyRowColumnSizeChange(new SheetRegion(dstRange.getSheet(),effected));
				}
			}
			
		}.doInWriteLock(getLock());		
		
	}

	@Override
	public void insert(final InsertShift shift, final InsertCopyOrigin copyOrigin) {
		new ModelManipulationTask() {
			@Override
			protected Object doInvoke() {
				new InsertDeleteHelper(RangeImpl.this).insert(shift, copyOrigin);
				return null;
			}
		}.doInWriteLock(getLock());
	}

	@Override
	public void delete(final DeleteShift shift) {
		new ModelManipulationTask() {
			@Override
			protected Object doInvoke() {
				new InsertDeleteHelper(RangeImpl.this).delete(shift);
				return null;
			}
		}.doInWriteLock(getLock());
	}

	@Override
	public void merge(final boolean across) {
		new ModelManipulationTask(){
			@Override
			protected Object doInvoke() {
				new MergeHelper(RangeImpl.this).merge(across);
				return null;
			}
			
		}.doInWriteLock(getLock());
	}

	@Override
	public void unmerge() {
		new ModelManipulationTask(){
			@Override
			protected Object doInvoke() {
				new MergeHelper(RangeImpl.this).unmerge(true);
				return null;
			}
			
		}.doInWriteLock(getLock());
	}

	@Override
	public void setBorders(final ApplyBorderType borderType,final BorderType lineStyle,
			final String color) {
		new ModelManipulationTask(){
			@Override
			protected Object doInvoke() {
				new BorderHelper(RangeImpl.this).applyBorder(borderType,lineStyle,color);
				return null;
			}
			
		}.doInWriteLock(getLock());
		
	}

	@Override
	public void move(final int nRow, final int nCol) {
		new ModelManipulationTask(){
			@Override
			protected Object doInvoke() {
				SSheet sheet = getSheet();
				sheet.moveCell(getRow(), getColumn(), getLastRow(), getLastColumn(), nRow, nCol); 
				return null;
			}
			
		}.doInWriteLock(getLock());
	}

	@Override
	public void setCellStyle(final SCellStyle style) {
		new ReadWriteTask(){
			@Override
			public Object invoke() {
				for (EffectedRegion r : _rangeRefs) {
					new SetCellStyleHelper(new RangeImpl(r.sheet,r.region)).setCellStyle(style);	
				}
				notifyChangeInLock(false);
				return null;
			}
		}.doInWriteLock(getLock());
	}	
	
	@Override
	public SCellStyle getCellStyle() {
		final ResultWrap<SCellStyle> r = new ResultWrap<SCellStyle>();
		new CellVisitorTask(new CellVisitor() {
			@Override
			public boolean visit(SCell cell) {
				r.set(cell.getCellStyle());		
				return false;
			}
		}).doInReadLock(getLock());
		return r.get();
	}

	@Override
	public void fill(final SRange dstRange, final FillType fillType) {
		SSheet sheet = getSheet();
		if(!dstRange.getSheet().equals(sheet)){
			throw new InvalidModelOpException("the source sheet and destination sheet aren't the same");
		}
		new ModelManipulationTask() {
			@Override
			protected Object doInvoke() {
				autoFillInLock(new CellRegion(getRow(),getColumn(),getLastRow(),getLastColumn()),
						new CellRegion(dstRange.getRow(),dstRange.getColumn(),dstRange.getLastRow(),dstRange.getLastColumn()), fillType);
				return null;
			}
		}.doInWriteLock(getLock());
	}
	
	private void autoFillInLock(CellRegion src,CellRegion dest, FillType fillType){
		SSheet sheet = getSheet();
		new AutoFillHelper().fill(sheet, src,dest, fillType);
	}

	@Override
	public void fillDown() {
		new ModelManipulationTask() {
			@Override
			protected Object doInvoke() {
				autoFillInLock(new CellRegion(getRow(),getColumn(),getRow(),getLastColumn()),
						new CellRegion(getRow(),getColumn(),getLastRow(),getLastColumn()), FillType.COPY);
				return null;
			}
		}.doInWriteLock(getLock());
	}

	@Override
	public void fillLeft() {
		new ModelManipulationTask() {
			@Override
			protected Object doInvoke() {
				autoFillInLock(new CellRegion(getRow(),getLastColumn(),getLastRow(),getLastColumn()),
						new CellRegion(getRow(),getColumn(),getLastRow(),getLastColumn()), FillType.COPY);
				return null;
			}
		}.doInWriteLock(getLock());
	}

	@Override
	public void fillRight() {
		new ModelManipulationTask() {
			@Override
			protected Object doInvoke() {
				autoFillInLock(new CellRegion(getRow(),getColumn(),getLastRow(),getColumn()),
						new CellRegion(getRow(),getColumn(),getLastRow(),getLastColumn()), FillType.COPY);
				return null;
			}
		}.doInWriteLock(getLock());
	}

	@Override
	public void fillUp() {
		new ModelManipulationTask() {
			@Override
			protected Object doInvoke() {
				autoFillInLock(new CellRegion(getLastRow(),getColumn(),getLastRow(),getLastColumn()),
						new CellRegion(getRow(),getColumn(),getLastRow(),getLastColumn()), FillType.COPY);
				return null;
			}
		}.doInWriteLock(getLock());
	}

	@Override
	public void setHidden(final boolean hidden) {
		new ReadWriteTask() {
			@Override
			public Object invoke() {
				setHiddenInLock(hidden);
				return null;
			}
		}.doInWriteLock(getLock());
	}
	
	
	private boolean isWholeRow(SBook book,CellRegion region){
		return region.column<=0 && region.lastColumn>=book.getMaxColumnIndex();
	}
	
	private boolean isWholeColumn(SBook book,CellRegion region){
		return region.row<=0 && region.lastRow>=book.getMaxRowIndex();
	}

	protected void setHiddenInLock(boolean hidden) {
		LinkedHashSet<SheetRegion> notifySet = new LinkedHashSet<SheetRegion>();
		for (EffectedRegion r : _rangeRefs) {
			SBook book = r.sheet.getBook();
			int maxcol = r.sheet.getBook().getMaxColumnIndex();
			int maxrow = r.sheet.getBook().getMaxRowIndex();
			CellRegion region = r.region;
			
			if(isWholeRow(book,region)){//hidden the row when it is whole row
				for(int i = region.getRow(); i<=region.getLastRow();i++){
					SRow row = r.sheet.getRow(i);
					if(row.isHidden()==hidden)
						continue;
					row.setHidden(hidden);
					notifySet.add(new SheetRegion(r.sheet,i,0,i,maxcol));
				}
			}else if(isWholeColumn(book,region)){
				for(int i = region.getColumn(); i<=region.getLastColumn();i++){
					SColumn col = r.sheet.getColumn(i);
					if(col.isHidden()==hidden)
						continue;
					col.setHidden(hidden);
					notifySet.add(new SheetRegion(r.sheet,0,i,maxrow,i));
				}
			}
		}
		new NotifyChangeHelper().notifyRowColumnSizeChange(notifySet);
	}

	@Override
	public void setDisplayGridlines(final boolean show) {
		new ReadWriteTask() {			
			@Override
			public Object invoke() {
				for (EffectedRegion r : _rangeRefs) {
					SSheet sheet = r.sheet;
					if(sheet.getViewInfo().isDisplayGridlines()!=show){
						sheet.getViewInfo().setDisplayGridlines(show);
						new NotifyChangeHelper().notifyDisplayGridlines(sheet,show);
					}
				}
				return null;
			}
		}.doInWriteLock(getLock());
		
	}

	@Override
	@Deprecated
	public void protectSheet(final String password) {
		new ReadWriteTask() {			
			@Override
			public Object invoke() {
				//20140423, henrichen: apply only to the first sheet 
				setPasswordInLock(getSheet(), password);
				return null;
			}
		}.doInWriteLock(getLock());
	}

	@Override
	public void setHyperlink(final HyperlinkType linkType,final String address,
			final String display) {
		new CellVisitorTask(new CellVisitorForUpdate() {
			public boolean visit(SCell cell) {
				SHyperlink link = cell.setupHyperlink(linkType,address,display);
				
				String text = display;
				while(text.startsWith("=")){
					text = text.substring(1);
				}
				cell.setStringValue(text);
				return true;
			}
		}).doInWriteLock(getLock());
	}

	@Override
	public Object getValue() {
		//Dennis, Should I follow the original implementation in BookHelper:getCellValue(Cell cell) ? it doesn't look appropriately to Range api.
		//I make this api become more easier to get the cell value (if it is formula, then get formula evaluation result
		
		final ResultWrap<Object> r = new ResultWrap<Object>();
		new CellVisitorTask(new CellVisitor() {
			@Override
			public boolean visit(SCell cell) {
				Object val = cell.getValue();
				r.set(val);
				return false;
			}
		}).doInReadLock(getLock());
		return r.get();
	}

	@Override
	public SRange getOffset(int rowOffset, int colOffset) {
		//follow the original XRange implementation
		if (rowOffset == 0 && colOffset == 0) { //no offset, return this
			return this;
		}
		if (_rangeRefs != null && !_rangeRefs.isEmpty()) {
			final SBook book = getBook();
			final int maxCol = book.getMaxColumnIndex();
			final int maxRow = book.getMaxRowIndex();
			final LinkedHashSet<SheetRegion> nrefs = new LinkedHashSet<SheetRegion>(_rangeRefs.size()); 

			for(EffectedRegion ref : _rangeRefs) {
				final int left = ref.region.getColumn() + colOffset;
				final int top = ref.region.getRow() + rowOffset;
				final int right = ref.region.getLastColumn() + colOffset;
				final int bottom = ref.region.getLastRow() + rowOffset;
				
				final SSheet refSheet = ref.sheet;
				final int nleft = colOffset < 0 ? Math.max(0, left) : left;  
				final int ntop = rowOffset < 0 ? Math.max(0, top) : top;
				final int nright = colOffset > 0 ? Math.min(maxCol, right) : right;
				final int nbottom = rowOffset > 0 ? Math.min(maxRow, bottom) : bottom;
				
				if (nleft > nright || ntop > nbottom) { //offset out of range
					continue;
				}
				final SheetRegion refAddr = new SheetRegion(refSheet,ntop, nleft, nbottom, nright);
				if (nrefs.contains(refAddr)) { //same area there, next
					continue;
				}
				nrefs.add(refAddr);
			}
			if (nrefs.isEmpty()) {
				return EMPTY_RANGE;
			} else{
				return new RangeImpl(nrefs);
			}
		}
		return EMPTY_RANGE;
	}

	@Override
	public boolean isAnyCellProtected() {
		return (Boolean)new ReadWriteTask(){
			@Override
			public Object invoke() {
				for (EffectedRegion r : _rangeRefs) {
					SSheet sheet = r.sheet;
					if(sheet.isProtected()){
						CellRegion region = r.region;
						for (int i = region.row; i <= region.lastRow; i++) {
							for (int j = region.column; j <= region.lastColumn; j++) {
								SCellStyle style = r.sheet.getCell(i, j).getCellStyle();
								if(style.isLocked()){
									return true;
								}
							}
						}
					}
				}
				return false;
			}
			
		}.doInReadLock(getLock());
	}

	@Override
	public void deleteSheet() {
		final ResultWrap<SSheet> toDeleteSheet = new ResultWrap<SSheet>();
		final ResultWrap<Integer> toDeleteIndex = new ResultWrap<Integer>();
		//it just handle the first ref
		new ModelManipulationTask() {			
			@Override
			protected Object doInvoke() {
				SBook book = getBook();
				int sheetCount;
				if((sheetCount = book.getNumOfSheet())<=1){
					throw new InvalidModelOpException("can't delete last sheet ");
				}
				
				SSheet toDelete = getSheet();
				
				int index = book.getSheetIndex(toDelete);
//				final int newIndex =  index < (sheetCount - 1) ? index : (index - 1);
				
				toDeleteSheet.set(toDelete);
				toDeleteIndex.set(index);
				
				book.deleteSheet(toDelete);
				return null;
			}

			@Override
			protected void doBeforeNotify() {
				if(toDeleteSheet.get()!=null){
					new NotifyChangeHelper().notifySheetDelete(getBook(), toDeleteSheet.get(), toDeleteIndex.get());
				}
			}
		}.doInWriteLock(getLock());
	}

	@Override
	public SSheet createSheet(final String name) {
		final ResultWrap<SSheet> resultSheet = new ResultWrap<SSheet>();
		//it just handle the first ref
		return (SSheet)new ModelManipulationTask() {			
			@Override
			protected Object doInvoke() {
				SBook book = getBook();
				SSheet sheet;
				if (Strings.isBlank(name)) {
					sheet = book.createSheet(nextSheetName());
				} else {
					sheet = book.createSheet(name);
				}
				resultSheet.set(sheet);
				return sheet;
			}

			@Override
			protected void doBeforeNotify() {
				if(resultSheet.get()!=null){
					new NotifyChangeHelper().notifySheetCreate(resultSheet.get());
				}
			}
		}.doInWriteLock(getLock());	
	}
	
	private String nextSheetName() {
		SBook book = getBook();
		Integer idx = (Integer)book.getAttribute("zss.nextSheetCount");
		int i = idx==null?1:idx;
		HashSet<String> names = new HashSet<String>();
		for (SSheet sheet : getBook().getSheets()) {
			names.add(sheet.getSheetName());
		}
		String base = "Sheet";
		String name = base + i; 
		while (names.contains(name)) {
			name = base+ (++i);
		}
		book.setAttribute("zss.nextSheetCount", Integer.valueOf(i+1));
		return name;
	}

	@Override
	public void setSheetName(final String newname) {
		//it just handle the first ref
		final ResultWrap<SSheet> resultSheet = new ResultWrap<SSheet>();
		final ResultWrap<String> oldName = new ResultWrap<String>();
		new ModelManipulationTask() {			
			@Override
			protected Object doInvoke() {
				SBook book = getBook();
				SSheet sheet = getSheet();
				String old = sheet.getSheetName();
				if(old.equals(newname)){
					return null;
				}
				book.setSheetName(sheet, newname);
				resultSheet.set(sheet);
				oldName.set(old);
				return null;
			}

			@Override
			protected void doBeforeNotify() {
				if(resultSheet.get()!=null){
					new NotifyChangeHelper().notifySheetNameChange(resultSheet.get(),oldName.get());
				}
			}
		}.doInWriteLock(getLock());	
	}

	@Override
	public void setSheetOrder(final int pos) {
		//it just handle the first ref
		final ResultWrap<SSheet> resultSheet = new ResultWrap<SSheet>();
		final ResultWrap<Integer> oldIdx = new ResultWrap<Integer>();
		new ModelManipulationTask() {			
			@Override
			protected Object doInvoke() {
				SBook book = getBook();
				SSheet sheet = getSheet();
				
				int old = book.getSheetIndex(sheet);
				if(old==pos){
					return null;
				}
				
				//in our new model, we don't use sheet index, so we don't need to clear anything when move it
				book.moveSheetTo(sheet, pos);
				resultSheet.set(sheet);
				oldIdx.set(old);
				return null;
			}

			@Override
			protected void doBeforeNotify() {
				if(resultSheet.get()!=null){
					new NotifyChangeHelper().notifySheetReorder(resultSheet.get(),oldIdx.get());
				}
			}
		}.doInWriteLock(getLock());	
	}

	@Override
	public void setFreezePanel(final int numOfRow, final int numOfColumn) {
		//first ref only
		new ReadWriteTask() {			
			@Override
			public Object invoke() {
				SSheetViewInfo viewInfo = getSheet().getViewInfo();
				viewInfo.setNumOfRowFreeze(numOfRow);
				viewInfo.setNumOfColumnFreeze(numOfColumn);
				notifySheetFreezeChange();
				return null;
			}
		}.doInWriteLock(getLock());	
	}
	
	private void notifySheetFreezeChange(){
		new NotifyChangeHelper().notifySheetFreezeChange(getSheet());
	}

	@Override
	public String getCellFormatText() {
		final ResultWrap<String> r = new ResultWrap<String>();
		new CellVisitorTask(new CellVisitor() {
			@Override
			public boolean visit(SCell cell) {
				FormatEngine fe = EngineFactory.getInstance().createFormatEngine();
				r.set(fe.format(cell, new FormatContext(ZssContext.getCurrent().getLocale())).getText());		
				return false;
			}
		}).doInReadLock(getLock());
		return r.get();
	}
	
	@Override
	public String getCellDataFormat() {
		final ResultWrap<String> r = new ResultWrap<String>();
		new CellVisitorTask(new CellVisitor() {
			@Override
			public boolean visit(SCell cell) {
				FormatEngine fe = EngineFactory.getInstance().createFormatEngine();
				r.set(fe.getFormat(cell, new FormatContext(ZssContext.getCurrent().getLocale())));		
				return false;
			}
		}).doInReadLock(getLock());
		return r.get();
	}

	@Override
	public boolean isSheetProtected() {
		//TODO do we really need to use lock in such simple call, it looks overkill.
		return (Boolean)new ReadWriteTask(){
			@Override
			public Object invoke() {
				return getSheet().isProtected();
			}}.doInReadLock(getLock());
	}

	@Override
	public SDataValidation validate(final String editText) {
		final ResultWrap<SDataValidation> retrunVal = new ResultWrap<SDataValidation>();
		new CellVisitorTask(new CellVisitor() {
			boolean visit(SCell cell) {
				SDataValidation validation = getSheet().getDataValidation(cell.getRowIndex(), cell.getColumnIndex());
				if(validation!=null){
					if(!new DataValidationHelper(validation).validate(editText,cell.getCellStyle().getDataFormat())){
						retrunVal.set(validation);
						return false;
					}
				}
				return true;
			}
		}).doInReadLock(getLock());
		return retrunVal.get();
	}

	@Override
	public SRange findAutoFilterRange() {
		return (SRange) new ReadWriteTask() {			
			@Override
			public Object invoke() {
				CellRegion region = new DataRegionHelper(RangeImpl.this).findAutoFilterDataRegion();
				if(region!=null){
					return SRanges.range(getSheet(),region.getRow(),region.getColumn(),region.getLastRow(),region.getLastColumn());
				}
				return null;
			}
		}.doInReadLock(getLock());
	}
	
	Ref getSheetRef(){
		return new RefImpl((AbstractSheetAdv)getSheet());
	}
	Ref getBookRef(){
		return new RefImpl((AbstractSheetAdv)getBook());
	}
	
	private Set<Ref> toSet(Ref ref){
		Set<Ref> refs = new HashSet(1);
		refs.add(ref);
		return refs;
	}
	
	@Override 
	public SAutoFilter enableAutoFilter(final boolean enable){
		//it just handle the first ref
		return (SAutoFilter) new ReadWriteTask() {			
			@Override
			public Object invoke() {
				SSheet sheet = getSheet();
				SAutoFilter filter = sheet.getAutoFilter();
				
				if((filter==null && !enable) || (filter!=null && enable)){
					return filter;
				}
				
				filter = new AutoFilterHelper(RangeImpl.this).enableAutoFilter(enable);
				notifySheetAutoFilterChange();
				return filter;
			}
		}.doInWriteLock(getLock());
	}

	@Override
	public SAutoFilter enableAutoFilter(final int field, final FilterOp filterOp,
			final Object criteria1, final Object criteria2, final Boolean visibleDropDown) {
		//it just handle the first ref
		return (SAutoFilter) new ReadWriteTask() {			
			@Override
			public Object invoke() {
				SAutoFilter filter = new AutoFilterHelper(RangeImpl.this).enableAutoFilter(field, filterOp, criteria1, criteria2, visibleDropDown);
				notifySheetAutoFilterChange();
				return filter;
			}
		}.doInWriteLock(getLock());
	}
	
	public void resetAutoFilter(){
		//it just handle the first ref
		new ReadWriteTask() {			
			@Override
			public Object invoke() {
				new AutoFilterHelper(RangeImpl.this).resetAutoFilter();
				notifySheetAutoFilterChange();
				return null;
			}
		}.doInWriteLock(getLock());		
	}
	
	public void applyAutoFilter(){
		//it just handle the first ref
		new ReadWriteTask() {			
			@Override
			public Object invoke() {
				new AutoFilterHelper(RangeImpl.this).applyAutoFilter();
				notifySheetAutoFilterChange();
				return null;
			}
		}.doInWriteLock(getLock());		
	}
	
	private void notifySheetAutoFilterChange(){
		new NotifyChangeHelper().notifySheetAutoFilterChange(getSheet());
	}

	@Override
	public void notifyCustomEvent(final String customEventName, final Object data, boolean writelock) {
		//it just handle the first ref
		ReadWriteTask task = new ReadWriteTask() {			
			@Override
			public Object invoke() {
				NotifyChangeHelper notifyHelper =  new NotifyChangeHelper();
				for (EffectedRegion r : _rangeRefs) {
					SBook book = r.sheet.getBook();
					notifyHelper.notifyCustomEvent(customEventName,r.sheet,data);
				}
				return null;
			}
		};
		if(writelock){
			task.doInWriteLock(getLock());
		}else{
			task.doInReadLock(getLock());
		}
	}
	/**
	 * The class that handle the common action when you manipulate the model.
	 * 1. the formula cache cleaner
	 * 2. the model update collector
	 *
	 */
	private abstract class ModelManipulationTask extends ReadWriteTask{
		
		protected abstract Object doInvoke();
		
		protected void doBeforeNotify(){
			//do nothing by default
		}
		protected void doAfterNotify(){
			//do nothing by default
		}
		
		/**
		 * return info about this task should collect model update or not, return false
		 * @return true by default
		 */
		protected boolean isCollectModelUpdate(){
			return true;
		}
		
		@Override
		public Object invoke() {
			ModelUpdateWrapper updateWrap = new ModelUpdateWrapper(getBookSeries(),isCollectModelUpdate());
			Object result = null;
			try{
				result = doInvoke();
			}finally{
				updateWrap.doFinially();
			}

			doBeforeNotify();
			updateWrap.doNotify();
			doAfterNotify();
			return result;
		}
	}
	
	private class ModelUpdateWrapper {
		SBookSeries bookSeries;
		
		List<ModelUpdate> modelUpdates;
		
		ModelUpdateCollector modelUpdateCollector;
		ModelUpdateCollector oldCollector;
		
		FormulaCacheCleaner oldClearer;
		
		/**
		 * @param bookSeries
		 * @param collectUpdate should collect model update or not
		 */
		public ModelUpdateWrapper(SBookSeries bookSeries,boolean collectUpdate){
			this.bookSeries = bookSeries;
			
			oldCollector = ModelUpdateCollector.setCurrent(collectUpdate?modelUpdateCollector = new ModelUpdateCollector():null);

			oldClearer = FormulaCacheCleaner.setCurrent(new FormulaCacheCleaner(bookSeries));
		}
		
		public void doFinially(){
			if(modelUpdateCollector!=null){
				modelUpdates = modelUpdateCollector.getModelUpdates();
			}
			ModelUpdateCollector.setCurrent(oldCollector);
			FormulaCacheCleaner.setCurrent(oldClearer);
		}
		
		public void doNotify(){
			if(modelUpdates==null)
				return;
			for(ModelUpdate update:modelUpdates){
				switch(update.getType()){
				case CELL:
					handleCellNotifyContentChange((SheetRegion)update.getData());
					break;
				case CELLS:
					handleCellNotifyContentChange((Set<SheetRegion>)update.getData());
					break;
				case REF:
					handleRefNotifyContentChange(bookSeries,(Ref)update.getData());
					break;
				case REFS:
					handleRefNotifyContentChange(bookSeries,(Set<Ref>)update.getData());
					break;
				case MERGE:
					MergeUpdate mu = (MergeUpdate)update.getData();
					if(mu.getOrigMerge()!=null){
						handleMergeRemoveNotifyChange(new SheetRegion(mu.getSheet(),mu.getOrigMerge()));
					}
					if(mu.getMerge()!=null){
						handleMergeAddNotifyChange(new SheetRegion(mu.getSheet(),mu.getMerge()));
					}
					break;
				case INSERT_DELETE:
					handleInsertDeleteNotifyChange(((InsertDeleteUpdate)update.getData()));
					break;
				}				
			}
		}
	}	

	@Override
	public SPicture addPicture(final ViewAnchor anchor, final byte[] image, final SPicture.Format format){
		return (SPicture) new ReadWriteTask() {			
			@Override
			public Object invoke() {
				SPicture picture = getSheet().addPicture(format, image, anchor);
				new NotifyChangeHelper().notifySheetPictureAdd(getSheet(), picture.getId());
				return picture;
			}
		}.doInWriteLock(getLock());
	}
	
	private void handleMergeRemoveNotifyChange(SheetRegion mergeNotify) {
		new NotifyChangeHelper().notifyMergeRemove(mergeNotify);
	}
	private void handleMergeRemoveNotifyChange(Set<SheetRegion> mergeNotifySet) {
		new NotifyChangeHelper().notifyMergeRemove(mergeNotifySet);
	}

	private void handleMergeAddNotifyChange(SheetRegion mergeNotify) {
		new NotifyChangeHelper().notifyMergeAdd(mergeNotify);
	}
	private void handleMergeAddNotifyChange(Set<SheetRegion> mergeNotifySet) {
		new NotifyChangeHelper().notifyMergeAdd(mergeNotifySet);
	}
	
	private void handleCellNotifyContentChange(SheetRegion cellNotify) {
		new NotifyChangeHelper().notifyCellChange(cellNotify);
	}
	private void handleCellNotifyContentChange(Set<SheetRegion> cellNotifySet) {
		new NotifyChangeHelper().notifyCellChange(cellNotifySet);
	}
	
	private void handleInsertDeleteNotifyChange(InsertDeleteUpdate insertDeleteNofity) {
		new NotifyChangeHelper().notifyInsertDelete(insertDeleteNofity);
	}
	private void handleInsertDeleteNotifyChange(List<InsertDeleteUpdate> insertDeleteNofitySet) {
		new NotifyChangeHelper().notifyInsertDelete(insertDeleteNofitySet);
	}

	@Override
	public void deletePicture(final SPicture picture){
		new ReadWriteTask() {			
			@Override
			public Object invoke() {
				String pid = picture.getId();
				getSheet().deletePicture(picture);
				new NotifyChangeHelper().notifySheetPictureDelete(getSheet(), pid);
				return null;
			}
		}.doInWriteLock(getLock());
	}
	
	@Override
	public void movePicture(final SPicture picture, final ViewAnchor anchor){
		new ReadWriteTask() {			
			@Override
			public Object invoke() {
				picture.setAnchor(anchor);
				new NotifyChangeHelper().notifySheetPictureMove(getSheet(), picture.getId());
				return null;
			}
		}.doInWriteLock(getLock());
	}
	
	@Override
	public SChart addChart(final ViewAnchor anchor, final ChartType type,	
			final ChartGrouping grouping, final ChartLegendPosition pos, final boolean isThreeD) {
		return (SChart) new ReadWriteTask() {			
			@Override
			public Object invoke() {
				SChart chart = getSheet().addChart(type, anchor);
				chart.setThreeD(isThreeD); 
				new ChartDataHelper(RangeImpl.this).fillChartData(chart);
				chart.setGrouping(grouping);
				chart.setLegendPosition(pos);
				new NotifyChangeHelper().notifySheetChartAdd(getSheet(), chart.getId());
				return chart;
			}
		}.doInWriteLock(getLock());
	}
	
	@Override
	public void deleteChart(final SChart chart){
		new ReadWriteTask() {			
			@Override
			public Object invoke() {
				getSheet().deleteChart(chart);
				new NotifyChangeHelper().notifySheetChartDelete(getSheet(), chart.getId());
				return null;
			}
		}.doInWriteLock(getLock());
	}
	
	@Override
	public void moveChart(final SChart chart, final ViewAnchor anchor) {
		new ReadWriteTask() {			
			@Override
			public Object invoke() {
				chart.setAnchor(anchor);
				new NotifyChangeHelper().notifySheetChartUpdate(getSheet(), chart.getId());
				return null;
			}
		}.doInWriteLock(getLock());
	}
	
	@Override
	public void updateChart(final SChart chart) {
		new ReadWriteTask() {			
			@Override
			public Object invoke() {
				new NotifyChangeHelper().notifySheetChartUpdate(getSheet(), chart.getId());
				return null;
			}
		}.doInWriteLock(getLock());
	}

	@Override
	public void sort(final SRange key1, final boolean descending1, final SortDataOption dataOption1, final SRange key2, final boolean descending2,
			final SortDataOption dataOption2, final SRange key3, final boolean descending3, final SortDataOption dataOption3,
			final int hasHeader, final boolean matchCase,final boolean sortByRows) {
		new ModelManipulationTask() {			
			@Override
			protected Object doInvoke() {
				new SortHelper(RangeImpl.this).sort(key1, descending1, dataOption1, key2, descending2, dataOption2,
					key3, descending3, dataOption3, hasHeader, matchCase, sortByRows);
				return null;
			}

			@Override
			protected void doBeforeNotify() {}
		}.doInWriteLock(getLock());			
	}

	//ZSS-294
	@Override
	public void createName(final String nameName) {
		new ModelManipulationTask() {			
			@Override
			protected Object doInvoke() {
				createNameInLock(nameName);
				return null;
			}
		}.doInWriteLock(getLock());
	}
	
	private void createNameInLock(String nameName) {
		final SSheet sht = getSheet();
		final String sn = sht.getSheetName();
		final int c1 = getColumn(), c2 = getLastColumn(), r1 = getRow(), r2 = getLastRow();
		
		String refers = null;
		if (c1 == c2 && r1 == r2) {
			final org.zkoss.poi.ss.util.CellReference cf = new org.zkoss.poi.ss.util.CellReference(sn, r1, c1, true, true);
			refers = cf.formatAsString();
		} else {
			final org.zkoss.poi.ss.util.AreaReference af = new org.zkoss.poi.ss.util.AreaReference(
					new org.zkoss.poi.ss.util.CellReference(sn,getRow(),c1,true,true), 
					new org.zkoss.poi.ss.util.CellReference(sn,getLastRow(),getLastColumn(),true,true));
			refers = af.formatAsString();
		}
		final SName name = getBook().createName(nameName);
		name.setRefersToFormula(refers);
		
		// Some formula might have referred to the {@link Name} by the specified nameName already; 
		// have to notify those cells.
		final AbstractBookSeriesAdv series = (AbstractBookSeriesAdv) getBookSeries();
		final DependencyTable table = series.getDependencyTable();
		handleRefNotifyContentChange(series, table.getEvaluatedDependents(new NameRefImpl((AbstractNameAdv)name)));
	}
	
	private static final SRange EMPTY_RANGE = new EmptyNRange();
	
	//ZSS-576
	//Refer #isAnyCellProtected() which checks ALL regions while this method
	//checks only the first region.
	@Override
	public boolean isProtected() {
		return (Boolean)new ReadWriteTask() {
			@Override
			public Object invoke() {
				//20140423, henrichen: we check only first Sheet
				EffectedRegion r = _rangeRefs.isEmpty() ? null : _rangeRefs.get(0);
				if (r != null) {
					SSheet sheet = r.sheet;
					if(sheet.isProtected()){
						CellRegion region = r.region;
						for (int i = region.row; i <= region.lastRow; i++) {
							for (int j = region.column; j <= region.lastColumn; j++) {
								SCellStyle style = r.sheet.getCell(i, j).getCellStyle();
								//as long as one is protected and locked, return true
								if(style.isLocked()) { 
									return true;
								}
							}
						}
					}
				}
				return false;
			}
		}.doInReadLock(getLock());
	}
	
	@Override
	public void protectSheet(final String password,  
		final boolean allowSelectingLockedCells, final boolean allowSelectingUnlockedCells,  
		final boolean allowFormattingCells, final boolean allowFormattingColumns, final boolean allowFormattingRows, 
		final boolean allowInsertColumns, final boolean allowInsertRows, final boolean allowInsertingHyperlinks,
		final boolean allowDeletingColumns, final boolean allowDeletingRows, 
		final boolean allowSorting, final boolean allowFiltering, final boolean allowUsingPivotTables, 
		final boolean drawingObjects, final boolean scenarios) {
		final String pass0 = password == null ? "" : password;
		new ReadWriteTask() {			
			@Override
			public Object invoke() {
				//20140423, henrichen: apply only to the first sheet
				protectSheetInLock(getSheet(), pass0,  
						allowSelectingLockedCells, allowSelectingUnlockedCells,  
						allowFormattingCells, allowFormattingColumns, allowFormattingRows, 
						allowInsertColumns, allowInsertRows, allowInsertingHyperlinks,
						allowDeletingColumns, allowDeletingRows, 
						allowSorting, allowFiltering, allowUsingPivotTables, 
						drawingObjects, scenarios);
				return null;
			}
		}.doInWriteLock(getLock());
	}
	
	//ZSS-576
	@Override
	public boolean unprotectSheet(final String password) {
		if (!getSheet().isProtected()) return true;
		return (Boolean) new ReadWriteTask() {
			@Override
			public Object invoke() {
				//20140423, henrichen: apply only to the first sheet
				return unprotectSheetInLock(getSheet(), password);
			}
		}.doInWriteLock(getLock());
	}
	
	private void protectSheetInLock(SSheet sht, String password,  
			boolean allowSelectingLockedCells, boolean allowSelectingUnlockedCells,  
			boolean allowFormattingCells, boolean allowFormattingColumns, boolean allowFormattingRows, 
			boolean allowInsertColumns, boolean allowInsertRows, boolean allowInsertingHyperlinks,
			boolean allowDeletingColumns, boolean allowDeletingRows, 
			boolean allowSorting, boolean allowFiltering, boolean allowUsingPivotTables, 
			boolean drawingObjects, boolean scenarios) {
		
		//check password
		if (sht.isProtected()) {
			final short hashpass = sht.getHashedPassword();
			final short inputpass = WorkbookUtil.hashPassword(password);
			if (inputpass != hashpass) {
				return;
			}
		}
		final SSheetProtection sp = sht.getSheetProtection();
		sp.setSelectLockedCells(allowSelectingLockedCells);
		sp.setSelectUnlockedCells(allowSelectingUnlockedCells);
		sp.setFormatCells(allowFormattingCells);
		sp.setFormatColumns(allowFormattingColumns);
		sp.setFormatRows(allowFormattingRows);
		sp.setInsertColumns(allowInsertColumns);
		sp.setInsertRows(allowInsertRows);
		sp.setInsertHyperlinks(allowInsertingHyperlinks);
		sp.setDeleteColumns(allowDeletingColumns);
		sp.setDeleteRows(allowDeletingRows);
		sp.setSort(allowSorting);
		sp.setAutoFilter(allowFiltering);
		sp.setPivotTables(allowUsingPivotTables);
		sp.setObjects(drawingObjects);
		sp.setScenarios(scenarios);
		
		setPasswordInLock(sht, password);
	}
	
	private boolean unprotectSheetInLock(SSheet sht, String password) {
		final short hashpass = sht.getHashedPassword();
		if (hashpass != 0) {
			// check password
			if (password == null || password.isEmpty() || WorkbookUtil.hashPassword(password) != hashpass) {
				return false;
			}
		}
		setPasswordInLock(sht, null);
		final SSheetProtection sp = sht.getSheetProtection();
		// 20140513, RaymondChao: excel behavior, objects and scenarios were set false after unprotected.
		sp.setObjects(false);
		sp.setScenarios(false);
		return true;
	}
	
	private void setPasswordInLock(SSheet sheet, String password) {
		if(sheet.isProtected() && password==null){
			sheet.setPassword(null);
			new NotifyChangeHelper().notifyProtectSheet(sheet,false);
		}else if(!sheet.isProtected() && password!=null){
			sheet.setPassword(password);
			new NotifyChangeHelper().notifyProtectSheet(sheet,true);
		}
	}

	@Override
	public SSheetProtection getSheetProtection() {
		return getSheet().getSheetProtection();
	}

	@Override
	public void setValidation(final ValidationType validationType,
			final boolean ignoreBlank, final OperatorType operatorType,
			final boolean inCellDropDown, final String formula1, final String formula2,
			final boolean showInput, final String inputTitle, final String inputMessage,
			final boolean showError, final AlertStyle alertStyle, final String errorTitle,
			final String errorMessage) {
		// empty validation
		if (validationType == ValidationType.ANY 
				&& inputTitle == null && inputMessage == null 
				&& errorTitle == null && errorMessage == null) {
			return;
		}
		new ReadWriteTask() {			
			@Override
			public Object invoke() {
				setValidaitonInLock(validationType,
						ignoreBlank, operatorType,
						inCellDropDown, formula1, formula2,
						showInput, inputTitle, inputMessage,
						showError, alertStyle, errorTitle,
						errorMessage);
				return null;
			}
		}.doInWriteLock(getLock());
	}
			
	private void setValidaitonInLock(ValidationType validationType,
			boolean ignoreBlank, OperatorType operatorType,
			boolean inCellDropDown, String formula1, String formula2,
			boolean showInput, String inputTitle, String inputMessage,
			boolean showError, AlertStyle alertStyle, String errorTitle,
			String errorMessage) {
		SDataValidation dv = getSheet().getDataValidation(getRow(), getColumn());
		if (dv == null) {
			dv = getSheet().addDataValidation(new CellRegion(getRow(), getColumn(), getLastRow(), getLastColumn()));
		}
		dv.setValidationType(validationType);
		dv.setIgnoreBlank(ignoreBlank);
		dv.setOperatorType(operatorType);
		dv.setInCellDropdown(inCellDropDown);
		((AbstractDataValidationAdv)dv).setFormulas(formula1, formula2);
		dv.setShowInput(showInput);
		dv.setInputTitle(inputTitle);
		dv.setInputMessage(inputMessage);
		dv.setShowError(showError);
		dv.setAlertStyle(alertStyle);
		dv.setErrorTitle(errorTitle);
		dv.setErrorMessage(errorMessage);
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<SDataValidation> getValidations() {
		return (List<SDataValidation>) new ReadWriteTask() {
			@Override
			public Object invoke() {
				final CellRegion region = new CellRegion(getRow(), getColumn(), getLastRow(), getLastColumn());
				List<SDataValidation> results = new ArrayList<SDataValidation>();
				for (SDataValidation dv: getSheet().getDataValidations()) {
					for (CellRegion rgn : dv.getRegions()) {
						if (rgn.overlaps(region)) {
							results.add(dv);
							break;
						}
					}
					if (results.size() > 1) break;
				}
				return results;
			}
		}.doInReadLock(getLock());
	}
	
	@Override
	public void deleteValidation() {
		new ReadWriteTask() {			
			@Override
			public Object invoke() {
				getSheet().removeDataValidationRegion( 
				  new CellRegion(getRow(), getColumn(), getLastRow(), getLastColumn()));
				return null;
			}
		}.doInWriteLock(getLock());
	}
}
