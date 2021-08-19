/**
 *
 * (c) Copyright Ascensio System SIA 2021
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package onlyoffice;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import onlyoffice.managers.configuration.ConfigurationManager;
import onlyoffice.managers.convert.ConvertManager;
import onlyoffice.managers.document.DocumentManager;
import onlyoffice.managers.jwt.JwtManager;
import onlyoffice.managers.url.UrlManager;
import onlyoffice.utils.attachment.AttachmentUtil;
import onlyoffice.utils.parsing.ParsingUtil;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.confluence.user.UserAccessor;
import com.atlassian.spring.container.ContainerManager;

import javax.inject.Inject;

public class OnlyOfficeSaveFileServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LogManager.getLogger("onlyoffice.OnlyOfficeSaveFileServlet");

    private final JwtManager jwtManager;
    private final DocumentManager documentManager;

    private final AttachmentUtil attachmentUtil;
    private final ParsingUtil parsingUtil;
    private final UrlManager urlManager;
    private final ConfigurationManager configurationManager;
    private final ConvertManager convertManager;

    @Inject
    public OnlyOfficeSaveFileServlet(JwtManager jwtManager, DocumentManager documentManager,
            AttachmentUtil attachmentUtil, ParsingUtil parsingUtil, UrlManager urlManager,
            ConfigurationManager configurationManager, ConvertManager convertManager) {
        this.jwtManager = jwtManager;
        this.documentManager = documentManager;
        this.attachmentUtil = attachmentUtil;
        this.parsingUtil = parsingUtil;
        this.urlManager = urlManager;
        this.configurationManager = configurationManager;
        this.convertManager = convertManager;
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String type = request.getParameter("type");
        if (type != null) {
            switch (type.toLowerCase())
            {
                case "track":
                    track(request, response);
                    break;
                case "create":
                    create(request, response);
                    break;
                default:
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
            }
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
    }


    private void track (HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain; charset=utf-8");

        String vkey = request.getParameter("vkey");
        log.info("vkey = " + vkey);
        String attachmentIdString = documentManager.readHash(vkey);

        String error = "";
        try {
            processData(attachmentIdString, request);
        } catch (Exception e) {
            error = e.getMessage();
        }

        PrintWriter writer = response.getWriter();
        if (error.isEmpty()) {
            writer.write("{\"error\":0}");
        } else {
            response.setStatus(500);
            writer.write("{\"error\":1,\"message\":\"" + error + "\"}");
        }

        log.info("error = " + error);
    }

    private void create (HttpServletRequest request, HttpServletResponse response) throws IOException {
        ConfluenceUser user = AuthenticatedUserThreadLocal.get();

        if (user == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        InputStream requestStream = request.getInputStream();
        String body = parsingUtil.getBody(requestStream);
        HttpURLConnection connection = null;

        try {
            JSONObject bodyJson = new JSONObject(body);
            String downloadUrl = bodyJson.getString("url");
            String title = bodyJson.getString("title");
            String ext = bodyJson.getString("ext");
            String pageIdString = bodyJson.getString("pageId");

            if (downloadUrl.isEmpty() || title.isEmpty() || ext.isEmpty() || pageIdString.isEmpty()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            Long pageId = Long.parseLong(pageIdString);

            if (!attachmentUtil.checkAccessCreate(user, pageId)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            URL url = new URL(downloadUrl);
            connection = (HttpURLConnection) url.openConnection();

            Integer timeout = Integer.parseInt(configurationManager.getProperty("timeout")) * 1000;
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);

            int size = connection.getContentLength();
            InputStream stream = connection.getInputStream();

            String fileName = documentManager.getCorrectName(title, ext, pageId);
            String mimeType = documentManager.getMimeType(fileName);

            attachmentUtil.createNewAttachment(fileName, mimeType, stream, size, pageId, user);
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }  finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void processData(String attachmentIdString, HttpServletRequest request) throws Exception {
        log.info("attachmentId = " + attachmentIdString);
        InputStream requestStream = request.getInputStream();
        if (attachmentIdString.isEmpty()) {
            throw new IllegalArgumentException("attachmentId is empty");
        }

        try {
            Long attachmentId = Long.parseLong(attachmentIdString);

            String body = parsingUtil.getBody(requestStream);
            log.info("body = " + body);
            if (body.isEmpty()) {
                throw new IllegalArgumentException("requestBody is empty");
            }

            JSONObject jsonObj = new JSONObject(body);

            if (jwtManager.jwtEnabled()) {
                String token = jsonObj.optString("token");
                Boolean inBody = true;

                if (token == null || token == "") {
                    String jwth = jwtManager.getJwtHeader();
                    String header = (String) request.getHeader(jwth);
                    token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : header;
                    inBody = false;
                }

                if (token == null || token == "") {
                    throw new SecurityException("Try save without JWT");
                }

                if (!jwtManager.verify(token)) {
                    throw new SecurityException("Try save with wrong JWT");
                }

                JSONObject bodyFromToken = new JSONObject(
                        new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), "UTF-8"));

                if (inBody) {
                    jsonObj = bodyFromToken;
                } else {
                    jsonObj = bodyFromToken.getJSONObject("payload");
                }
            }

            long status = jsonObj.getLong("status");
            log.info("status = " + status);

            ConfluenceUser user = getConfluenceUserFromJSON(jsonObj);
            log.info("user = " + user);

            if (status == 1) {
                if (jsonObj.has("actions")) {
                    JSONArray actions = jsonObj.getJSONArray("actions");
                    if (actions.length() > 0) {
                        JSONObject action = (JSONObject) actions.get(0);
                        if (action.getLong("type") == 1) {
                            if (attachmentUtil.getCollaborativeEditingKey(attachmentId) == null) {
                                String key = jsonObj.getString("key");
                                attachmentUtil.setCollaborativeEditingKey(attachmentId, key);
                            }
                        }
                    }
                }
            }

            // MustSave, Corrupted
            if (status == 2 || status == 3) {
                if (user != null && attachmentUtil.checkAccess(attachmentId, user, true)) {
                    String downloadUrl = jsonObj.getString("url");
                    downloadUrl = urlManager.replaceDocEditorURLToInternal(downloadUrl);
                    log.info("downloadUri = " + downloadUrl);

                    attachmentUtil.setCollaborativeEditingKey(attachmentId, null);
                    saveAttachmentFromUrl(attachmentId, downloadUrl, user);

                    String history = jsonObj.getString("history");
                    String changesUrl = urlManager.replaceDocEditorURLToInternal(jsonObj.getString("changesurl"));
                    log.info("changesUri = " + downloadUrl);

                    attachmentUtil.saveAttachmentChanges(attachmentId, history, changesUrl);
                } else {
                    throw new SecurityException("Try save without access: " + user);
                }
            }

            if (status == 4) {
                attachmentUtil.setCollaborativeEditingKey(attachmentId, null);
            }

            // MustForceSave, CorruptedForceSave
            if (status == 6 || status == 7) {
                if (user != null && attachmentUtil.checkAccess(attachmentId, user, true)) {
                    if (configurationManager.forceSaveEnabled()) {
                        String downloadUrl = jsonObj.getString("url");
                        downloadUrl = urlManager.replaceDocEditorURLToInternal(downloadUrl);
                        log.info("downloadUri = " + downloadUrl);

                        String key = attachmentUtil.getCollaborativeEditingKey(attachmentId);
                        attachmentUtil.setCollaborativeEditingKey(attachmentId, null);
                        saveAttachmentFromUrl(attachmentId, downloadUrl, user);
                        attachmentUtil.setCollaborativeEditingKey(attachmentId, key);

                        String history = jsonObj.getString("history");
                        String changesUrl = urlManager.replaceDocEditorURLToInternal(jsonObj.getString("changesurl"));
                        log.info("changesUri = " + downloadUrl);

                        attachmentUtil.saveAttachmentChanges(attachmentId, history, changesUrl);
                    } else {
                        log.info("Forcesave is disabled, ignoring forcesave request");
                    }
                } else {
                    throw new SecurityException("Try save without access: " + user);
                }
            }
        } catch (Exception ex) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            String error = ex.toString() + "\n" + sw.toString();
            log.error(error);

            throw ex;
        }
    }

    private void saveAttachmentFromUrl (Long attachmentId, String downloadUrl, ConfluenceUser user) throws Exception {
        HttpURLConnection connection = null;
        try {
            List<String> defaultEditingTypes = configurationManager.getDefaultEditingTypes();;

            String attachmentExt = attachmentUtil.getFileExt(attachmentId);
            String extDownloadUrl = downloadUrl.substring(downloadUrl.lastIndexOf(".") + 1);
            if (!defaultEditingTypes.contains(attachmentExt)) {
                JSONObject response = convertManager.convert(attachmentId, extDownloadUrl, attachmentExt, downloadUrl, false);
                downloadUrl = response.getString("fileUrl");
            }

            URL url = new URL(downloadUrl);
            connection = (HttpURLConnection) url.openConnection();

            Integer timeout = Integer.parseInt(configurationManager.getProperty("timeout")) * 1000;
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);

            int size = connection.getContentLength();
            InputStream stream = connection.getInputStream();

            attachmentUtil.saveAttachment(attachmentId, stream, size, user);
        } catch (Exception e) {
            throw e;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private ConfluenceUser getConfluenceUserFromJSON (JSONObject jsonObj) throws JSONException {
        ConfluenceUser confluenceUser = null;
        if (jsonObj.has("users")) {
            JSONArray users = jsonObj.getJSONArray("users");
            if (users.length() > 0) {
                String userName = users.getString(0);
                UserAccessor userAccessor = (UserAccessor) ContainerManager.getComponent("userAccessor");
                confluenceUser = userAccessor.getUserByName(userName);
            }
        }
        return confluenceUser;
    }
}
