package onlyoffice.managers.url;

import onlyoffice.model.config.DocumentType;

import java.io.Serializable;

public interface UrlManager extends Serializable {
    String getPublicDocEditorUrl();

    String getInnerDocEditorUrl();

    String getFileUri(Long attachmentId);

    String getAttachmentDiffUri(Long attachmentId);

    String getHistoryInfoUri(Long attachmentId);

    String getHistoryDataUri(Long attachmentId);

    String getAttachmentDataUri();

    String getSaveAsUri();

    String getReferenceDataUri(Long pageId);

    String getCallbackUrl(Long attachmentId);

    String getGobackUrl(Long attachmentId, String referer);

    String getCreateUri(Long pageId, String ext);

    String replaceDocEditorURLToInternal(String url);

    String getDocServiceApiUrl();

    String getFaviconUrl(DocumentType documentType);
}
