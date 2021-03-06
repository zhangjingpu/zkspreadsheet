/* order_test_1Test.java

	Purpose:
		
	Description:
		
	History:
		Sep, 7, 2010 17:30:59 PM

Copyright (C) 2010 Potix Corporation. All Rights Reserved.

This program is distributed under Apache License Version 2.0 in the hope that
it will be useful, but WITHOUT ANY WARRANTY.
*/


//change row 12 height to 40
public class SS_117_Test extends SSAbstractTestCase {

	@Override
	protected void executeTest() {
		String f13value = getSpecifiedCell(5,12).text();
		rightClickRowHeader(11);
		click(jq("$rowHeight a.z-menu-item-cnt"));
		waitResponse();
		type(jq("$headerSize"), "40");
		waitResponse();
		click(jq("$okBtn td.z-button-cm"));
		waitResponse();

//		int height = getSpecifiedCell(5,11).height();
//		System.out.println(">>>height =" + height);
		//too rigid
//		verifyTrue(getSpecifiedCell(5,11).height() == 40);
		verifyTrue(getSpecifiedCell(5,11).height() >= 40-2);
		verifyTrue(getSpecifiedCell(5,11).height() <= 40+2);
	}
}



