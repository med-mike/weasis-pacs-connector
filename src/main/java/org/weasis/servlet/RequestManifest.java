/*******************************************************************************
 * Copyright (c) 2014 Weasis Team.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 *******************************************************************************/
package org.weasis.servlet;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.util.StringUtil;
import org.weasis.dicom.wado.WadoQuery;
import org.weasis.dicom.wado.XmlManifest;
import org.weasis.dicom.wado.thread.ManifestBuilder;
import org.weasis.dicom.wado.thread.ManifestManagerThread;

public class RequestManifest extends HttpServlet {

    private static final long serialVersionUID = 3012016354418267374L;
    private static Logger LOGGER = LoggerFactory.getLogger(RequestManifest.class);

    public static final String PARAM_ID = "id";
    public static final String PARAM_NO_GZIP = "noGzip";

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        response.setStatus(HttpServletResponse.SC_ACCEPTED);

        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate"); // HTTP 1.1
        response.setHeader("Pragma", "no-cache"); // HTTP 1.0
        response.setDateHeader("Expires", -1); // Proxies

        String wadoXmlId = request.getParameter(PARAM_ID);
        Integer id = null;
        try {
            id = StringUtil.hasText(wadoXmlId) ? Integer.parseInt(wadoXmlId) : null;
        } catch (NumberFormatException e1) {
            // Do nothing
        }

        if (id == null) {
            String errorMsg = "Missing or bad 'id' parameter in request";
            LOGGER.error(errorMsg);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, errorMsg);
            return;
        }
        LOGGER.debug("doGet [id={}] - START", id);

        ConcurrentHashMap<Integer, ManifestBuilder> threadsMap = null;
        threadsMap =
            (ConcurrentHashMap<Integer, ManifestBuilder>) getServletContext().getAttribute("manifestBuilderMap");

        if (threadsMap == null) {
            String errorMsg = "Missing 'ManifestBuilderMap' from current ServletContext";
            LOGGER.error(errorMsg);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorMsg);
            return;
        }

        ManifestBuilder buidler = threadsMap.get(id);

        if (buidler == null) {
            String errorMsg = "No 'ManifestBuilder' found with id=" + id;
            LOGGER.warn(errorMsg);
            response.sendError(HttpServletResponse.SC_NOT_FOUND, errorMsg);
            return;
        }

        XmlManifest xml = null;

        try {
            Future<XmlManifest> future = buidler.getFuture();
            if (future != null) {
                xml = future.get(ManifestManagerThread.MAX_LIFE_CYCLE, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e1) {
            LOGGER.error("Building Manifest Exception [id={}] - {}", id, e1.toString());
        }

        threadsMap.remove(id);
        LOGGER.info("Consume ManifestBuilder with key={}", id);

        if (xml == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Cannot build Manifest [id=" + id + "]");
            return;
        }

        response.setCharacterEncoding(xml.getCharsetEncoding());
        String wadoXmlGenerated = xml.xmlManifest();

        Boolean gzip = request.getParameter(PARAM_NO_GZIP) == null;

        if (gzip && xml.getWadoMessage() == null) {
            OutputStream outputStream = null;
            try {
                outputStream = response.getOutputStream();
                GZIPOutputStream gzipStream = new GZIPOutputStream(outputStream);

                response.setContentType("application/x-gzip");
                response.setHeader("Content-Disposition", "filename=\"manifest-" + id + ".gz\";");

                gzipStream.write(wadoXmlGenerated.getBytes());
                gzipStream.finish();
            } catch (Exception e) {
                String errorMsg = "Exception writing GZIP response [id=" + id + "]";
                LOGGER.error("{} - {}", errorMsg, e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorMsg);
                return;
            } finally {
                try {
                    if (outputStream != null) {
                        outputStream.close();
                    }
                } catch (Exception doNothing) {
                }
            }
        } else {
            PrintWriter writer = null;
            try {
                writer = response.getWriter();
                response.setContentType("text/xml");
                response.setHeader("Content-Disposition", "filename=\"manifest-" + id + ".xml\";");
                response.setContentLength(wadoXmlGenerated.length());
                writer.print(wadoXmlGenerated);
            } catch (Exception e) {
                String errorMsg = "Exception writing noGzip response [id=" + id + "]";
                LOGGER.error("{} - {}", errorMsg, e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorMsg);
                return;
            } finally {
                if (writer != null) {
                    writer.close();
                }
            }
        }

        response.setStatus(HttpServletResponse.SC_OK);
    }
}
