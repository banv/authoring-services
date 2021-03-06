package org.ihtsdo.snowowl.authoring.batchimport.api.pojo.batch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.ihtsdo.otf.rest.client.snowowl.pojo.DefinitionStatus;
import org.ihtsdo.otf.rest.exception.ProcessingException;
import org.ihtsdo.snowowl.authoring.batchimport.api.service.SnomedBrowserConstants;

public class BatchImportExpression implements SnomedBrowserConstants {
	
	public static final String FULLY_DEFINED = "===";
	public static final String PRIMITVE = "<<<";
	public static final String PIPE = "|";
	public static final char PIPE_CHAR = '|';
	public static final char SPACE = ' ';
	public static final char[] REFINEMENT_START = new char[] {':', '{'};
	public static final String GROUP_START = "\\{";
	public static final char GROUP_START_CHAR = '{';
	public static final String GROUP_END = "}";
	public static final char GROUP_END_CHAR = '}';
	public static final String FOCUS_CONCEPT_SEPARATOR = "\\+";
	public static final String ATTRIBUTE_SEPARATOR = ",";
	public static final String TYPE_SEPARATOR = "=";
	public static char[] termTerminators = new char[] {'|', ':', '+', '{', ',', '}', '=' };

	private DefinitionStatus definitionStatus;
	private List<String> focusConcepts;
	private List<BatchImportGroup> attributeGroups;

	private BatchImportExpression(){
		
	}
	
	public static BatchImportExpression parse(String expressionStr, String moduleId) throws ProcessingException {
		BatchImportExpression result = new BatchImportExpression();
		StringBuffer expressionBuff = new StringBuffer(expressionStr);
		makeMachineReadable(expressionBuff);
		//After each extract we're left with the remainder of the expression
		result.definitionStatus = extractDefinitionStatus(expressionBuff);
		result.focusConcepts = extractFocusConcepts(expressionBuff);
		result.attributeGroups = extractGroups(expressionBuff, moduleId);
		return result;
	}

	static DefinitionStatus extractDefinitionStatus(StringBuffer expressionBuff) throws ProcessingException {
		Boolean isFullyDefined = null;
		if (expressionBuff.indexOf(FULLY_DEFINED) == 0) {
			isFullyDefined = Boolean.TRUE;
		} else if (expressionBuff.indexOf(PRIMITVE) == 0) {
			isFullyDefined = Boolean.FALSE;
		}
		
		if (isFullyDefined == null) {
			throw new ProcessingException("Unable to determine Definition Status from: " + expressionBuff);
		}
		
		expressionBuff.delete(0, FULLY_DEFINED.length());
		return isFullyDefined ? DefinitionStatus.FULLY_DEFINED : DefinitionStatus.PRIMITIVE;
	}
	
	static List<String> extractFocusConcepts(StringBuffer expressionBuff) {
		// Do we have a refinement, or just the parent(s) defined?
		//Ah, we're sometimes missing the colon.  Allow open curly brace as well.
		int focusEnd = indexOf(expressionBuff, REFINEMENT_START, 0);
		if (focusEnd == -1) {
			//Otherwise cut to end
			focusEnd = expressionBuff.length();
		} 
		String focusConceptStr = expressionBuff.substring(0, focusEnd);
		String[] focusConcepts = focusConceptStr.split(FOCUS_CONCEPT_SEPARATOR);
		if (focusEnd < expressionBuff.length() && expressionBuff.charAt(focusEnd) == ':') {
			//Also delete the ":" symbol
			focusEnd++;
		}
		expressionBuff.delete(0, focusEnd);
		return Arrays.asList(focusConcepts);
	}

	static List<BatchImportGroup> extractGroups(StringBuffer expressionBuff, String moduleId) throws ProcessingException {
		List<BatchImportGroup> groups = new ArrayList<>();
		//Do we have any groups to parse?
		if (expressionBuff == null || expressionBuff.length() == 0) {
			return groups;
		} else if (expressionBuff.charAt(0) == GROUP_START_CHAR) {
			remove(expressionBuff,GROUP_END_CHAR);
			expressionBuff.deleteCharAt(0);
			String[] arrGroup = expressionBuff.toString().split(GROUP_START);
			int groupNumber = 0;
			for (String thisGroupStr : arrGroup) {
				BatchImportGroup newGroup = BatchImportGroup.parse(++groupNumber, thisGroupStr, moduleId);
				groups.add(newGroup);
			}
		} else if (Character.isDigit(expressionBuff.charAt(0))) {
			//Do we have a block of ungrouped attributes to start with?  parse
			//up to the first open group character, ensuring that it occurs before the 
			//next close group character.
			int nextGroupOpen = expressionBuff.indexOf(Character.toString(GROUP_START_CHAR));
			int nextGroupClose = expressionBuff.indexOf(Character.toString(GROUP_END_CHAR));
			//Case no further groups
			if (nextGroupOpen == -1 && nextGroupClose == -1) {
				BatchImportGroup newGroup = BatchImportGroup.parse(0, expressionBuff.toString(), moduleId);
				groups.add(newGroup);
			} else if (nextGroupOpen > -1 && nextGroupClose > nextGroupOpen) {
				BatchImportGroup newGroup = BatchImportGroup.parse(0, expressionBuff.substring(0, nextGroupOpen), moduleId);
				groups.add(newGroup);
				//And now work through the bracketed groups
				StringBuffer remainder = new StringBuffer(expressionBuff.substring(nextGroupOpen, expressionBuff.length()));
				groups.addAll(extractGroups(remainder, moduleId));
			} else {
				throw new ProcessingException("Unable to separate grouped from ungrouped attributes in: " + expressionBuff.toString());
			}
		} else {
			throw new ProcessingException("Unable to parse attributes groups from: " + expressionBuff.toString());
		}
		return groups;
	}
	
	static void makeMachineReadable (StringBuffer hrExp) {
		int pipeIdx =  hrExp.indexOf(PIPE);
		while (pipeIdx != -1) {
			int endIdx = findEndOfTerm(hrExp, pipeIdx);
			hrExp.delete(pipeIdx, endIdx);
			pipeIdx =  hrExp.indexOf(PIPE);
		}
		remove(hrExp, SPACE);
	}
	
	private static int findEndOfTerm(StringBuffer hrExp, int searchStart) {
		int endIdx = indexOf(hrExp, termTerminators, searchStart+1);
		//If we didn't find a terminator, cut to the end.
		if (endIdx == -1) {
			endIdx = hrExp.length();
		} else {
			//If the character found as a terminator is a pipe, then cut that too
			if (hrExp.charAt(endIdx) == PIPE_CHAR) {
				endIdx++;
			} else if (hrExp.charAt(endIdx) == ATTRIBUTE_SEPARATOR.charAt(0)) {
				//If the character is a comma, then it might be a comma inside a term so find out if the next token is a number
				if (!StringUtils.isNumericSpace(hrExp.substring(endIdx+1, endIdx+5))) {
					//OK it's a term, so find the new actual end. 
					endIdx = findEndOfTerm(hrExp, endIdx);
				}
			}
		}
		return endIdx;
	}

	static void remove (StringBuffer haystack, char needle) {
		for (int idx = 0; idx < haystack.length(); idx++) {
			if (haystack.charAt(idx) == needle) {
				haystack.deleteCharAt(idx);
				idx --;
			}
		}
	}
	
	
	static int indexOf (StringBuffer haystack, char[] needles, int startFrom) {
		for (int idx = startFrom; idx < haystack.length(); idx++) {
			for (char thisNeedle : needles) {
				if (haystack.charAt(idx) == thisNeedle) {
					return idx;
				}
			}
		}
		return -1;
	}

	public DefinitionStatus getDefinitionStatus() {
		return definitionStatus;
	}

	public List<String> getFocusConcepts() {
		return focusConcepts;
	}

	public List<BatchImportGroup> getAttributeGroups() {
		return attributeGroups;
	}
}
