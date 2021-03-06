package org.zkoss.zss.api.impl.formula;

import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.zkoss.zss.Setup;
import org.zkoss.zss.Util;
import org.zkoss.zss.api.model.Book;

@Ignore
public class FinancialUnsupportedTest extends FormulaTestBase {
	
	@BeforeClass
	public static void setUpLibrary() throws Exception {
		Setup.touch();
	}
	
	@Before
	public void startUp() throws Exception {
		Setup.pushZssLocale(Locale.TAIWAN);
	}
	
	@After
	public void tearDown() throws Exception {
		Setup.popZssLocale();
	}
	
	// #NAME?
	@Test
	public void testLOGEST()  {
		Book book = Util.loadBook(this,"TestFile2007-Formula.xlsx");
		testLOGEST(book);
	}
	
	// #NAME?
	@Test
	public void testMIRR()  {
		Book book = Util.loadBook(this,"TestFile2007-Formula.xlsx");
		testMIRR(book);
	}
	
	// #NAME?
	@Test
	public void testISPMT()  {
		Book book = Util.loadBook(this,"TestFile2007-Formula.xlsx");
		testISPMT(book);
	}
	
	// #NAME?
	@Test
	public void testVDB()  {
		Book book = Util.loadBook(this,"TestFile2007-Formula.xlsx");
		testVDB(book);
	}
}
