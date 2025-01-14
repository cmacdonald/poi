/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */

package org.apache.poi.ooxml.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.events.Namespace;

import org.apache.poi.util.POILogFactory;
import org.apache.poi.util.POILogger;
import org.w3c.dom.*;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

public final class DocumentHelper {
    private static POILogger logger = POILogFactory.getLogger(DocumentHelper.class);
    private static long lastLog;

    private DocumentHelper() {}

    private static class DocHelperErrorHandler implements ErrorHandler {

        public void warning(SAXParseException exception) {
            printError(POILogger.WARN, exception);
        }

        public void error(SAXParseException exception) {
            printError(POILogger.ERROR, exception);
        }

        public void fatalError(SAXParseException exception) throws SAXException {
            printError(POILogger.FATAL, exception);
            throw exception;
        }

        /** Prints the error message. */
        private void printError(int type, SAXParseException ex) {
            StringBuilder sb = new StringBuilder();
            
            String systemId = ex.getSystemId();
            if (systemId != null) {
                int index = systemId.lastIndexOf('/');
                if (index != -1)
                    systemId = systemId.substring(index + 1);
                sb.append(systemId);
            }
            sb.append(':');
            sb.append(ex.getLineNumber());
            sb.append(':');
            sb.append(ex.getColumnNumber());
            sb.append(": ");
            sb.append(ex.getMessage());

            logger.log(type, sb.toString(), ex);
        }
    }
    
    /**
     * Creates a new document builder, with sensible defaults
     *
     * @throws IllegalStateException If creating the DocumentBuilder fails, e.g.
     *  due to {@link ParserConfigurationException}.
     */
    public static DocumentBuilder newDocumentBuilder() {
        try {
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            documentBuilder.setEntityResolver(SAXHelper.IGNORING_ENTITY_RESOLVER);
            documentBuilder.setErrorHandler(new DocHelperErrorHandler());
            return documentBuilder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("cannot create a DocumentBuilder", e);
        }
    }

    static final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
    static {
        documentBuilderFactory.setNamespaceAware(true);
        documentBuilderFactory.setValidating(false);
        //this doesn't appear to work, and we still need to limit
        //entity expansions to 1 in trySetXercesSecurityManager
        documentBuilderFactory.setExpandEntityReferences(false);
        trySetFeature(documentBuilderFactory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        trySetFeature(documentBuilderFactory, POIXMLConstants.FEATURE_DISALLOW_DOCTYPE_DECL, true);
        trySetFeature(documentBuilderFactory, POIXMLConstants.FEATURE_LOAD_DTD_GRAMMAR, false);
        trySetFeature(documentBuilderFactory, POIXMLConstants.FEATURE_LOAD_EXTERNAL_DTD, false);
        trySetXercesSecurityManager(documentBuilderFactory);
    }

    private static void trySetFeature(@SuppressWarnings("SameParameterValue") DocumentBuilderFactory dbf, String feature, boolean enabled) {
        try {
            dbf.setFeature(feature, enabled);
        } catch (Exception e) {
            logger.log(POILogger.WARN, "DocumentBuilderFactory Feature unsupported", feature, e);
        } catch (AbstractMethodError ame) {
            logger.log(POILogger.WARN, "Cannot set DocumentBuilderFactory feature because outdated XML parser in classpath", feature, ame);
        }
    }
    
    private static void trySetXercesSecurityManager(@SuppressWarnings("SameParameterValue") DocumentBuilderFactory dbf) {
        // Try built-in JVM one first, standalone if not
        for (String securityManagerClassName : new String[]{
                //"com.sun.org.apache.xerces.internal.util.SecurityManager",
                "org.apache.xerces.util.SecurityManager"
        }) {
            try {
                Object mgr = Class.forName(securityManagerClassName).newInstance();
                Method setLimit = mgr.getClass().getMethod("setEntityExpansionLimit", Integer.TYPE);
                setLimit.invoke(mgr, 1);
                dbf.setAttribute(POIXMLConstants.PROPERTY_SECURITY_MANAGER, mgr);
                // Stop once one can be setup without error
                return;
            } catch (ClassNotFoundException e) {
                // continue without log, this is expected in some setups
            } catch (Throwable e) {     // NOSONAR - also catch things like NoClassDefError here
                if(System.currentTimeMillis() > lastLog + TimeUnit.MINUTES.toMillis(5)) {
                    logger.log(POILogger.WARN, "DocumentBuilderFactory Security Manager could not be setup [log suppressed for 5 minutes]", e);
                    lastLog = System.currentTimeMillis();
                }
            }
        }

        // separate old version of Xerces not found => use the builtin way of setting the property
        // Note: when entity_expansion_limit==0, there is no limit!
        try {
            dbf.setAttribute(POIXMLConstants.PROPERTY_ENTITY_EXPANSION_LIMIT, 1);
        } catch (Throwable e) {
            if(System.currentTimeMillis() > lastLog + TimeUnit.MINUTES.toMillis(5)) {
                logger.log(POILogger.WARN, "DocumentBuilderFactory Entity Expansion Limit could not be setup [log suppressed for 5 minutes]", e);
                lastLog = System.currentTimeMillis();
            }
        }
    }

    /**
     * Parses the given stream via the default (sensible)
     * DocumentBuilder
     * @param inp Stream to read the XML data from
     * @return the parsed Document 
     */
    public static Document readDocument(InputStream inp) throws IOException, SAXException {
        return newDocumentBuilder().parse(inp);
    }

    /**
     * Parses the given stream via the default (sensible)
     * DocumentBuilder
     * @param inp sax source to read the XML data from
     * @return the parsed Document 
     */
    public static Document readDocument(InputSource inp) throws IOException, SAXException {
        return newDocumentBuilder().parse(inp);
    }

    // must only be used to create empty documents, do not use it for parsing!
    private static final DocumentBuilder documentBuilderSingleton = newDocumentBuilder();

    /**
     * Creates a new DOM Document
     */
    public static Document createDocument() {
        return documentBuilderSingleton.newDocument();
    }

    /**
     * Adds a namespace declaration attribute to the given element.
     */
    public static void addNamespaceDeclaration(Element element, String namespacePrefix, String namespaceURI) {
        element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                XMLConstants.XMLNS_ATTRIBUTE + ':' + namespacePrefix,
                namespaceURI);
    }

    /**
     * Adds a namespace declaration attribute to the given element.
     */
    public static void addNamespaceDeclaration(Element element, Namespace namespace) {
        addNamespaceDeclaration(element, namespace.getPrefix(), namespace.getNamespaceURI());
    }
}
