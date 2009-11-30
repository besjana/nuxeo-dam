package org.nuxeo.dam.webapp.contentbrowser;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.faces.context.FacesContext;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.remoting.WebRemote;
import org.jboss.seam.contexts.Context;
import org.jboss.seam.contexts.Contexts;
import org.jboss.seam.core.Events;
import org.nuxeo.dam.webapp.PictureActions;
import org.nuxeo.dam.webapp.helper.DownloadHelper;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentLocation;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.PagedDocumentsProvider;
import org.nuxeo.ecm.core.api.impl.DocumentLocationImpl;
import org.nuxeo.ecm.platform.actions.Action;
import org.nuxeo.ecm.platform.forms.layout.api.BuiltinModes;
import org.nuxeo.ecm.platform.picture.api.adapters.PictureResourceAdapter;
import org.nuxeo.ecm.platform.ui.web.api.WebActions;
import org.nuxeo.ecm.platform.ui.web.tag.fn.DocumentModelFunctions;
import org.nuxeo.ecm.platform.url.DocumentViewImpl;
import org.nuxeo.ecm.platform.url.api.DocumentView;
import org.nuxeo.ecm.platform.url.codec.DocumentFileCodec;
import org.nuxeo.ecm.platform.util.RepositoryLocation;
import org.nuxeo.ecm.webapp.delegate.DocumentManagerBusinessDelegate;
import org.nuxeo.ecm.webapp.documentsLists.DocumentsListsManager;
import org.nuxeo.ecm.webapp.helpers.EventNames;
import org.nuxeo.ecm.webapp.helpers.ResourcesAccessor;
import org.nuxeo.ecm.webapp.pagination.ResultsProvidersCache;

@Name("documentActions")
@Scope(ScopeType.CONVERSATION)
public class DocumentActions implements Serializable {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings("unused")
    private static final Log log = LogFactory.getLog(DocumentActions.class);

    protected static final long BIG_FILE_SIZE_LIMIT = 1024 * 1024 * 5;

    protected static final String DEFAULT_PICTURE_DOWNLOAD_PROPERTY = "Original";

    @In(create = true)
    protected transient ResultsProvidersCache resultsProvidersCache;

    @In(required = false, create = true)
    protected transient DocumentsListsManager documentsListsManager;

    @In(create = true, required = false)
    protected transient CoreSession documentManager;

    @In
    protected transient Context conversationContext;

    @In(create = true)
    protected PictureActions pictureActions;

    @In(create = true)
    protected transient WebActions webActions;

    @In(create = true)
    protected ResourcesAccessor resourcesAccessor;

    /**
     * Current selected asset
     */
    protected DocumentModel currentSelection;

    /**
     * Current selection link - defines the fragment to be shown under the tabs
     * list
     */
    protected String currentSelectionLink;

    protected String displayMode = BuiltinModes.VIEW;

    protected String downloadSize = DEFAULT_PICTURE_DOWNLOAD_PROPERTY;

    @WebRemote
    public String processSelectRow(String docRef, String providerName,
            String listName, Boolean selection) {
        PagedDocumentsProvider provider;
        try {
            provider = resultsProvidersCache.get(providerName);
        } catch (ClientException e) {
            return handleError(e.getMessage());
        }
        DocumentModel doc = null;
        for (DocumentModel pagedDoc : provider.getCurrentPage()) {
            if (pagedDoc.getRef().toString().equals(docRef)) {
                doc = pagedDoc;
                break;
            }
        }
        if (doc == null) {
            return handleError(String.format(
                    "could not find doc '%s' in the current page of provider '%s'",
                    docRef, providerName));
        }
        listName = (listName == null) ? DocumentsListsManager.CURRENT_DOCUMENT_SELECTION
                : listName;
        if (selection) {
            documentsListsManager.addToWorkingList(listName, doc);
        } else {
            documentsListsManager.removeFromWorkingList(listName, doc);
        }
        // TODO: handle actions
        return "";
    }

    @WebRemote
    public String processSelectPage(String providerName, String listName,
            Boolean selection) {
        PagedDocumentsProvider provider;
        try {
            provider = resultsProvidersCache.get(providerName);
        } catch (ClientException e) {
            return handleError(e.getMessage());
        }
        DocumentModelList documents = provider.getCurrentPage();
        String lName = (listName == null) ? DocumentsListsManager.CURRENT_DOCUMENT_SELECTION
                : listName;
        if (selection) {
            documentsListsManager.addToWorkingList(lName, documents);
        } else {
            documentsListsManager.removeFromWorkingList(lName, documents);
        }
        raiseEvents(currentSelection);
        // TODO handle action management
        return "";
    }

    private String handleError(String errorMessage) {
        return "ERROR: " + errorMessage;
    }

    public DocumentModel getCurrentSelection() {
        return currentSelection;
    }

    public void setCurrentSelection(DocumentModel selection) {
        // Reset the tabs list and the display mode
        webActions.resetTabList();
        displayMode = BuiltinModes.VIEW;

        currentSelection = selection;

        // Set first tab as current tab
        List<Action> tabList = webActions.getTabsList();
        if (tabList != null && tabList.size() > 0) {
            Action currentAction = tabList.get(0);
            webActions.setCurrentTabAction(currentAction);
            currentSelectionLink = currentAction.getLink();
        }
        resetData();
        raiseEvents(currentSelection);
    }

    public String getCurrentSelectionLink() {
        if (currentSelectionLink == null) {
            return "/incl/tabs/empty_tab.xhtml";
        }
        return currentSelectionLink;
    }

    public void setCurrentTabAction(Action currentTabAction) {
        webActions.setCurrentTabAction(currentTabAction);
        currentSelectionLink = currentTabAction.getLink();
    }

    public String getDisplayMode() {
        return displayMode;
    }

    public void toggleDisplayMode() {
        if (BuiltinModes.VIEW.equals(displayMode)) {
            displayMode = BuiltinModes.EDIT;
        } else {
            displayMode = BuiltinModes.VIEW;
        }
    }

    public void updateCurrentSelection() throws ClientException {
        if (currentSelection != null) {
            documentManager.saveDocument(currentSelection);
            documentManager.save();

            // Switch to view mode
            displayMode = BuiltinModes.VIEW;
        }
    }

    /**
     * Takes in a DocumentModel, gets the 'title' from it, and crops it to a
     * maximum of maxLength characters. If the Title is more than maxLength
     * characters it will return the Beginning of the title, followed by 3
     * ellipses (...) followed by the End of the title.
     * 
     * A minimum of 6 characters is needed before cropping takes effect. If you
     * specify a maxLength of less than 5, it is ignored - in this case
     * maxLength will be set to begin at 5.
     * 
     * @param DocumentModel document to extract the title from
     * @param int maxLength the maximum length of the title before cropping will
     *        occur
     * @return String with the cropped title restricted to maximum of maxLength
     *         characters
     */
    public String getTitleCropped(DocumentModel document, int maxLength) {
        int nbrEllipses = 3;
        int minLength = 5;

        String title;
        title = DocumentModelFunctions.titleOrId(document);

        int length = title.length();

        // a minimum of 5 characters needed before we crop
        if (length <= minLength) {
            return title;
        }

        // if maxLength is crazy, set it to a proper value
        if (maxLength <= minLength) {
            maxLength = minLength;
        }

        if (length <= maxLength) {
            return title;
        }

        // at this point we should be ok to start cropping to our heart's
        // content
        // length is more than maxLength characters: construct the new title

        // get the first (maxLength-3)/2 characters:
        int nbrBeginningChars;
        if ((maxLength - nbrEllipses) % 2 == 0) {
            nbrBeginningChars = (maxLength - nbrEllipses) / 2;
        } else {
            nbrBeginningChars = (maxLength - nbrEllipses) / 2 + 1;
        }

        String beginningChars = title.substring(0, nbrBeginningChars);
        // get the last n characters:
        int nbrEndChars = maxLength - nbrBeginningChars - nbrEllipses;
        String endChars = title.substring(length - nbrEndChars, length);

        String croppedTitle = beginningChars + "..." + endChars;
        return croppedTitle;

    }

    public void download(DocumentView docView) throws ClientException {
        if (docView != null) {
            DocumentLocation docLoc = docView.getDocumentLocation();
            if (documentManager == null) {
                RepositoryLocation loc = new RepositoryLocation(
                        docLoc.getServerName());
                documentManager = getOrCreateDocumentManager(loc);
            }
            DocumentModel doc = documentManager.getDocument(docLoc.getDocRef());
            if (doc != null) {
                // get properties from document view
                String filename = DocumentFileCodec.getFilename(doc, docView);
                // download
                FacesContext context = FacesContext.getCurrentInstance();
                DownloadHelper.download(
                        context,
                        doc,
                        docView.getParameter(DocumentFileCodec.FILE_PROPERTY_PATH_KEY),
                        filename);
            }
        }
    }

    protected CoreSession getOrCreateDocumentManager(
            RepositoryLocation repositoryLocation) throws ClientException {
        if (documentManager != null) {
            return documentManager;
        }
        DocumentManagerBusinessDelegate documentManagerBD = (DocumentManagerBusinessDelegate) Contexts.lookupInStatefulContexts("documentManager");
        if (documentManagerBD == null) {
            // this is the first time we select the location, create a
            // DocumentManagerBusinessDelegate instance
            documentManagerBD = new DocumentManagerBusinessDelegate();
            conversationContext.set("documentManager", documentManagerBD);
        }
        documentManager = documentManagerBD.getDocumentManager(repositoryLocation);
        return documentManager;
    }

    public String downloadBlob() throws ClientException {
        if (currentSelection != null) {
            if (currentSelection.hasSchema("file")) {
                DocumentLocation docLoc = new DocumentLocationImpl(
                        currentSelection);
                Map<String, String> params = new HashMap<String, String>();
                params.put(DocumentFileCodec.FILE_PROPERTY_PATH_KEY,
                        "file:content");
                params.put(
                        DocumentFileCodec.FILENAME_KEY,
                        (String) currentSelection.getPropertyValue("file:filename"));
                DocumentView docView = new DocumentViewImpl(docLoc, null,
                        params);

                download(docView);
            }

            if (currentSelection.hasSchema("picture")) {
                PictureResourceAdapter pra = currentSelection.getAdapter(PictureResourceAdapter.class);
                String xpath = pra.getViewXPath(downloadSize);
                String filename = (String) currentSelection.getPropertyValue(xpath
                        + "filename");
                String blobXpath = xpath + "content";
                FacesContext context = FacesContext.getCurrentInstance();
                DownloadHelper.download(context, currentSelection, blobXpath,
                        filename);
            }
        }

        return null;
    }

    private void resetData() {
        // Data to reset on asset selection is changed
        downloadSize = DEFAULT_PICTURE_DOWNLOAD_PROPERTY;
    }

    public String getDownloadSize() {
        return downloadSize;
    }

    public void setDownloadSize(String downloadSize) {
        this.downloadSize = downloadSize;
    }

    public void rewind(PagedDocumentsProvider provider) {
        provider.rewind();
        setCurrentSelectionBasedOnProvider(provider);
    }

    public void previous(PagedDocumentsProvider provider) {
        provider.previous();
        setCurrentSelectionBasedOnProvider(provider);
    }

    public void next(PagedDocumentsProvider provider) {
        provider.next();
        setCurrentSelectionBasedOnProvider(provider);
    }

    public void last(PagedDocumentsProvider provider) {
        provider.last();
        setCurrentSelectionBasedOnProvider(provider);
    }

    private void setCurrentSelectionBasedOnProvider(
            PagedDocumentsProvider provider) {
        // CB: DAM-235 - On a page, first asset must be always selected
        DocumentModelList currentPage = provider.getCurrentPage();
        if (currentPage != null && !currentPage.isEmpty()) {
            currentSelection = currentPage.get(0);
        }
    }

    public static void raiseEvents(DocumentModel document) {
        Events eventManager = Events.instance();
        eventManager.raiseEvent(EventNames.DOCUMENT_SELECTION_CHANGED, document);
    }
}
