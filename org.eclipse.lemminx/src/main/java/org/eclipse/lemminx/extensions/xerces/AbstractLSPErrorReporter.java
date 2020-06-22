/**
 *  Copyright (c) 2018 Angelo ZERR
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v2.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 *  Contributors:
 *  Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 */
package org.eclipse.lemminx.extensions.xerces;

import java.util.List;

import org.apache.xerces.impl.XMLErrorReporter;
import org.apache.xerces.impl.msg.XMLMessageFormatter;
import org.apache.xerces.impl.xs.XSMessageFormatter;
import org.apache.xerces.util.MessageFormatter;
import org.apache.xerces.xni.XMLLocator;
import org.apache.xerces.xni.XNIException;
import org.apache.xerces.xni.parser.XMLParseException;
import org.eclipse.lemminx.commons.BadLocationException;
import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.extensions.xerces.xmlmodel.msg.XMLModelMessageFormatter;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.xml.sax.ErrorHandler;

/**
 * The SAX {@link ErrorHandler} gives just information of the offset where there
 * is an error. To improve highlight XML error, this class extends the Xerces
 * XML reporter to catch location, key, arguments which is helpful to adjust the
 * LSP range.
 *
 */
public abstract class AbstractLSPErrorReporter extends XMLErrorReporter {

	private final DOMDocument xmlDocument;
	private final List<Diagnostic> diagnostics;

	private final String source;

	public AbstractLSPErrorReporter(String source, DOMDocument xmlDocument, List<Diagnostic> diagnostics) {
		this.source = source;
		this.xmlDocument = xmlDocument;
		this.diagnostics = diagnostics;
		XMLMessageFormatter xmft = new XMLMessageFormatter();
		super.putMessageFormatter(XMLMessageFormatter.XML_DOMAIN, xmft);
		super.putMessageFormatter(XMLMessageFormatter.XMLNS_DOMAIN, xmft);
		super.putMessageFormatter(XSMessageFormatter.SCHEMA_DOMAIN, new LSPMessageFormatter());
		super.putMessageFormatter(XMLModelMessageFormatter.XML_MODEL_DOMAIN, new XMLModelMessageFormatter());
	}

	public String reportError(XMLLocator location, String domain, String key, Object[] arguments, short severity,
			Exception exception) throws XNIException {
		// format message
		MessageFormatter messageFormatter = getMessageFormatter(domain);
		String message;
		if (messageFormatter != null) {
			message = messageFormatter.formatMessage(fLocale, key, arguments);
		} else {
			StringBuilder str = new StringBuilder();
			str.append(domain);
			str.append('#');
			str.append(key);
			int argCount = arguments != null ? arguments.length : 0;
			if (argCount > 0) {
				str.append('?');
				for (int i = 0; i < argCount; i++) {
					str.append(arguments[i]);
					if (i < argCount - 1) {
						str.append('&');
					}
				}
			}
			message = str.toString();
		}

		Range adjustedRange = internalToLSPRange(location, key, arguments, xmlDocument);

		if (adjustedRange == null) {
			return null;
		}

		if (!addDiagnostic(adjustedRange, message, toLSPSeverity(severity), key)) {
			return null;
		}

		if (severity == SEVERITY_FATAL_ERROR && !fContinueAfterFatalError && !isIgnoreFatalError(key)) {
			XMLParseException parseException = (exception != null) ? new XMLParseException(location, message, exception)
					: new XMLParseException(location, message);
			throw parseException;
		}
		return message;
	}

	protected boolean isIgnoreFatalError(String key) {
		return false;
	}

	public boolean addDiagnostic(Range adjustedRange, String message, DiagnosticSeverity severity, String key) {
		Diagnostic d = new Diagnostic(adjustedRange, message, severity, source, key);
		if (diagnostics.contains(d)) {
			return false;
		}
		// Fill diagnostic
		diagnostics.add(d);
		return true;
	}

	/**
	 * Returns the LSP diagnostic severity according the SAX severity.
	 * 
	 * @param severity the SAX severity
	 * @return the LSP diagnostic severity according the SAX severity.
	 */
	private static DiagnosticSeverity toLSPSeverity(int severity) {
		switch (severity) {
		case SEVERITY_WARNING:
			return DiagnosticSeverity.Warning;
		default:
			return DiagnosticSeverity.Error;
		}
	}

	/**
	 * Create the LSP range from the SAX error.
	 * 
	 * @param location
	 * @param key
	 * @param arguments
	 * @param document
	 * @return the LSP range from the SAX error.
	 */
	private Range internalToLSPRange(XMLLocator location, String key, Object[] arguments, DOMDocument document) {
		if (location == null) {
			Position start = toLSPPosition(0, location, document.getTextDocument());
			Position end = toLSPPosition(0, location, document.getTextDocument());
			return new Range(start, end);
		}

		Range range = toLSPRange(location, key, arguments, document);
		if (range != null) {
			return range;
		}
		int startOffset = location.getCharacterOffset() - 1;
		int endOffset = location.getCharacterOffset() - 1;

		if (startOffset < 0 || endOffset < 0) {
			return null;
		}

		// Create LSP range
		Position start = toLSPPosition(startOffset, location, document.getTextDocument());
		Position end = toLSPPosition(endOffset, location, document.getTextDocument());
		return new Range(start, end);
	}

	protected abstract Range toLSPRange(XMLLocator location, String key, Object[] arguments, DOMDocument document);
	

	/**
	 * Returns the LSP position from the SAX location.
	 * 
	 * @param offset   the adjusted offset.
	 * @param location the original SAX location.
	 * @param document the text document.
	 * @return the LSP position from the SAX location.
	 */
	private static Position toLSPPosition(int offset, XMLLocator location, TextDocument document) {
		if (location != null && offset == location.getCharacterOffset() - 1) {
			return new Position(location.getLineNumber() - 1, location.getColumnNumber() - 1);
		}
		try {
			return document.positionAt(offset);
		} catch (BadLocationException e) {
			return location != null ? new Position(location.getLineNumber() - 1, location.getColumnNumber() - 1) : null;
		}
	}
}