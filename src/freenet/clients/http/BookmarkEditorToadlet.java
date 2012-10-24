package freenet.clients.http;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.bookmark.Bookmark;
import freenet.clients.http.bookmark.BookmarkCategory;
import freenet.clients.http.bookmark.BookmarkItem;
import freenet.clients.http.bookmark.BookmarkManager;
import freenet.clients.http.uielements.*;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.DarknetPeerNode;
import freenet.node.NodeClientCore;
import freenet.support.*;
import freenet.support.Logger.LogLevel;
import freenet.support.api.HTTPRequest;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;

import static freenet.clients.http.QueueToadlet.MAX_KEY_LENGTH;

/**
 * BookmarkEditor Toadlet 
 * 
 * Accessible from http://.../bookmarkEditor/
 */
public class BookmarkEditorToadlet extends Toadlet {
	private static final int MAX_ACTION_LENGTH = 20;
	/** Max. bookmark name length */
	private static final int MAX_NAME_LENGTH = 500;
	/** Max. bookmark path length (e.g. <code>Freenet related software and documentation/Freenet Message System</code> ) */
	private static final int MAX_BOOKMARK_PATH_LENGTH = 10 * MAX_NAME_LENGTH;
	private static final int MAX_EXPLANATION_LENGTH = 1024;
	
	private final NodeClientCore core;
	private final BookmarkManager bookmarkManager;
	private String cutedPath;

        private static volatile boolean logDEBUG;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	BookmarkEditorToadlet(HighLevelSimpleClient client, NodeClientCore core, BookmarkManager bookmarks) {
		super(client);
		this.core = core;
		this.bookmarkManager = bookmarks;
		this.cutedPath = null;
	}

	/**
	 * Get all bookmark as a tree of &lt;li&gt;...&lt;/li&gt;s
	 */
	private void addCategoryToList(BookmarkCategory cat, String path, OutputList bookmarkList) {
		List<BookmarkItem> items = cat.getItems();

		final String edit = NodeL10n.getBase().getString("BookmarkEditorToadlet.edit");
		final String delete = NodeL10n.getBase().getString("BookmarkEditorToadlet.delete");
		final String cut = NodeL10n.getBase().getString("BookmarkEditorToadlet.cut");
		final String moveUp = NodeL10n.getBase().getString("BookmarkEditorToadlet.moveUp");
		final String moveDown = NodeL10n.getBase().getString("BookmarkEditorToadlet.moveDown");
		final String paste = NodeL10n.getBase().getString("BookmarkEditorToadlet.paste");
		final String addBookmark = NodeL10n.getBase().getString("BookmarkEditorToadlet.addBookmark");
		final String addCategory = NodeL10n.getBase().getString("BookmarkEditorToadlet.addCategory");

		boolean hasFriends = core.node.getDarknetConnections().length > 0;

		for(int i = 0; i < items.size(); i++) {
			BookmarkItem item =  items.get(i);

			String itemPath = URLEncoder.encode(path + item.getName(), false);
			Item bookmarkItem = bookmarkList.addItem(HTMLClass.ITEM, item.getVisibleName());
			String explain = item.getShortDescription();
			if(explain != null && explain.length() > 0) {
				bookmarkItem.addText(" (");
				bookmarkItem.addText(explain);
				bookmarkItem.addText(")");
			}

			InlineBox actions = new InlineBox(HTMLClass.ACTIONS);
			actions.addLink("?action=edit&bookmark=" + itemPath).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/edit.png", edit, edit});
			actions.addLink("?action=del&bookmark=" + itemPath).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/delete.png", delete, delete});
			if(cutedPath == null) {
				actions.addLink("?action=cut&bookmark=" + itemPath).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/cut.png", cut, cut});
			}
			if(i != 0) {
				actions.addLink("?action=up&bookmark=" + itemPath).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/go-up.png", moveUp, moveUp});
			}
			if(i != items.size() - 1) {
				actions.addLink("?action=down&bookmark=" + itemPath).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/go-down.png", moveDown, moveDown});
			}
			if(hasFriends) {
				actions.addLink("?action=share&bookmark=" + itemPath, NodeL10n.getBase().getString("BookmarkEditorToadlet.share"));
			}
			bookmarkItem.addChild(actions);
		}

		List<BookmarkCategory> cats = cat.getSubCategories();
		for(int i = 0; i < cats.size(); i++) {
			String catPath = path + cats.get(i).getName() + '/';
			String catPathEncoded = URLEncoder.encode(catPath, false);

			Item subCat = bookmarkList.addItem(HTMLClass.CAT, cats.get(i).getVisibleName());

			InlineBox actions = new InlineBox(HTMLClass.ACTIONS);
			actions.addLink("?action=edit&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/edit.png", edit, edit});
			actions.addLink("?action=del&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/delete.png", delete, delete});
			actions.addLink("?action=addItem&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/bookmark-new.png", addBookmark, addBookmark});
			actions.addLink("?action=addCat&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/folder-new.png", addCategory, addCategory});
			if(cutedPath == null) {
				actions.addLink("?action=cut&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/cut.png", cut, cut});
			}
			if(i != 0) {
				actions.addLink("?action=up&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/go-up.png", moveUp, moveUp});
			}
			if(i != cats.size() - 1) {
				actions.addLink("?action=down&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/go-down.png", moveDown, moveDown});
			}
			if(cutedPath != null && !catPathEncoded.startsWith(cutedPath) && !catPathEncoded.equals(bookmarkManager.parentPath(cutedPath))) {
				actions.addLink("?action=paste&bookmark=" + catPathEncoded).addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/paste.png", paste, paste});
			}
			subCat.addChild(actions);
			if (cats.get(i).size() != 0) {
				addCategoryToList(cats.get(i), catPath, bookmarkList.addItem().addList());
			}
		}
	}

	private void sendBookmarkFeeds(HTTPRequest req, BookmarkItem item, String publicDescription) {
		for(DarknetPeerNode peer : core.node.getDarknetConnections())
			if(req.isPartSet("node_" + peer.hashCode()))
				peer.sendBookmarkFeed(item.getURI(), item.getName(), publicDescription, item.hasAnActivelink());
	}

	public OutputList getBookmarksList() {
		OutputList bookmarks = new OutputList(HTMLID.BOOKMARKS);

		Item root = bookmarks.addItem(HTMLClass.CAT);
		root.addClass(HTMLClass.ROOT);
		root.addText("/");
		InlineBox actions = root.addInlineBox(HTMLClass.ACTIONS);
		String addBookmark = NodeL10n.getBase().getString("BookmarkEditorToadlet.addBookmark");
		String addCategory = NodeL10n.getBase().getString("BookmarkEditorToadlet.addCategory");
		String paste = NodeL10n.getBase().getString("BookmarkEditorToadlet.paste");
		actions.addLink("?action=addItem&bookmark=/").addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/bookmark-new.png", addBookmark, addBookmark});
		actions.addLink("?action=addCat&bookmark=/").addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/folder-new.png", addCategory, addCategory});
		if(cutedPath != null && !"/".equals(bookmarkManager.parentPath(cutedPath))) {
			actions.addLink("?action=paste&bookmark=/").addChild("img", new String[]{"src", "alt", "title"}, new String[]{"/static/icon/paste.png", paste, paste});
		}
		addCategoryToList(BookmarkManager.MAIN_CATEGORY, "/", root.addList());
		return bookmarks;
	}

	public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx)
		throws ToadletContextClosedException, IOException {
			if(!ctx.isAllowedFullAccess()) {
				super.sendErrorPage(ctx, 403, NodeL10n.getBase().getString("Toadlet.unauthorizedTitle"), NodeL10n.getBase().getString("Toadlet.unauthorized"));
				return;
			}
		PageMaker pageMaker = ctx.getPageMaker();
		String editorTitle = NodeL10n.getBase().getString("BookmarkEditorToadlet.title");
		String error = NodeL10n.getBase().getString("BookmarkEditorToadlet.error");
		PageNode page = pageMaker.getPageNode(editorTitle, ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode content = page.content;
		String originalBookmark = req.getParam("bookmark");
		if(req.getParam("action").length() > 0 && originalBookmark.length() > 0) {
			String action = req.getParam("action");
			String bookmarkPath;
			try {
				bookmarkPath = URLDecoder.decode(originalBookmark, false);
			} catch (URLEncodedFormatException e) {
				InfoboxWidget DecodeError = new InfoboxWidget(InfoboxWidget.Type.ERROR, HTMLClass.BOOKMARKURLDECODEERROR, error);
				content.addChild(DecodeError);
				DecodeError.body.addText(NodeL10n.getBase().getString("BookmarkEditorToadlet.urlDecodeError"));
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			}
			Bookmark bookmark;

			if(bookmarkPath.endsWith("/"))
				bookmark = bookmarkManager.getCategoryByPath(bookmarkPath);
			else
				bookmark = bookmarkManager.getItemByPath(bookmarkPath);

			if (bookmark == null) {
				InfoboxWidget BookmarkMissing = new InfoboxWidget(InfoboxWidget.Type.ERROR, HTMLClass.BOOKMARKDOESNOTEXIST, error);
				content.addChild(BookmarkMissing);
				BookmarkMissing.body.addText(NodeL10n.getBase().getString("BookmarkEditorToadlet.bookmarkDoesNotExist", new String[]{"bookmark"}, new String[]{bookmarkPath}));
				this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			} else
				if ("del".equals(action)) {

					String[] bm = new String[]{"bookmark"};
					String[] path = new String[]{bookmarkPath};
					String queryTitle = NodeL10n.getBase().getString("BookmarkEditorToadlet." + ((bookmark instanceof BookmarkItem) ? "deleteBookmark" : "deleteCategory"));
					InfoboxWidget ConfirmDeleteBookmark = new InfoboxWidget(InfoboxWidget.Type.QUERY, HTMLClass.BOOKMARKDELETE, queryTitle);
					content.addChild(ConfirmDeleteBookmark);

					String query = NodeL10n.getBase().getString("BookmarkEditorToadlet." + ((bookmark instanceof BookmarkItem) ? "deleteBookmarkConfirm" : "deleteCategoryConfirm"), bm, path);
					ConfirmDeleteBookmark.body.addBlockText(query);

					HTMLNode confirmForm = ctx.addFormChild(ConfirmDeleteBookmark.body, "", "confirmDeleteForm");
					confirmForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"hidden", "bookmark", bookmarkPath});
					confirmForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});
					confirmForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"submit", "confirmdelete", NodeL10n.getBase().getString("BookmarkEditorToadlet.confirmDelete")});

				} else if("cut".equals(action))
					cutedPath = bookmarkPath;
				else if("paste".equals(action) && cutedPath != null) {

					bookmarkManager.moveBookmark(cutedPath, bookmarkPath);
					bookmarkManager.storeBookmarks();
					cutedPath = null;

				} else if("edit".equals(action) || "addItem".equals(action) || "addCat".equals(action) || "share".equals(action)) {
					boolean isNew = "addItem".equals(action) || "addCat".equals(action);
					String header;
					if("edit".equals(action))
						header = NodeL10n.getBase().getString("BookmarkEditorToadlet.edit" + ((bookmark instanceof BookmarkItem) ? "Bookmark" : "Category") + "Title");
					else if("addItem".equals(action))
						header = NodeL10n.getBase().getString("BookmarkEditorToadlet.addNewBookmark");
					else if("share".equals(action))
						header = NodeL10n.getBase().getString("BookmarkEditorToadlet.share");
					else
						header = NodeL10n.getBase().getString("BookmarkEditorToadlet.addNewCategory");

					InfoboxWidget BookmarkAction = new InfoboxWidget(InfoboxWidget.Type.QUERY, HTMLClass.BOOKMARKACTION, header);
					content.addChild(BookmarkAction);
					HTMLNode form = ctx.addFormChild(BookmarkAction.body, "", "editBookmarkForm");

					form.addChild("label", "for", "name", (NodeL10n.getBase().getString("BookmarkEditorToadlet.nameLabel") + ' '));
					form.addChild("input", new String[]{"type", "id", "name", "size", "value"}, new String[]{"text", "name", "name", "20", !isNew ? bookmark.getVisibleName() : ""});

					form.addLineBreak();
					if(("edit".equals(action) && bookmark instanceof BookmarkItem) || "addItem".equals(action) || "share".equals(action)) {
						BookmarkItem item = isNew ? null : (BookmarkItem) bookmark;
						String key = !isNew ? item.getKey() : "";
						form.addChild("label", "for", "key", (NodeL10n.getBase().getString("BookmarkEditorToadlet.keyLabel") + ' '));
						form.addChild("input", new String[] {"type", "id", "name", "size", "value"}, new String[] {"text", "key", "key", "50", key});
						form.addLineBreak();
						if("edit".equals(action) || "addItem".equals(action)) {
							form.addChild("label", "for", "descB", (NodeL10n.getBase().getString("BookmarkEditorToadlet.descLabel") + ' '));
							form.addLineBreak();
							form.addChild("textarea", new String[]{"id", "name", "row", "cols"}, new String[]{"descB", "descB", "3", "70"}, (isNew ? "" : item.getDescription()));
							form.addLineBreak();
							form.addChild("label", "for", "descB", (NodeL10n.getBase().getString("BookmarkEditorToadlet.explainLabel") + ' '));
							form.addLineBreak();
							form.addChild("textarea", new String[]{"id", "name", "row", "cols"}, new String[]{"explain", "explain", "3", "70"}, (isNew ? "" : item.getShortDescription()));
							form.addLineBreak();
						}
						form.addChild("label", "for", "hasAnActivelink", (NodeL10n.getBase().getString("BookmarkEditorToadlet.hasAnActivelinkLabel") + ' '));
						if(!isNew && item.hasAnActivelink())
							form.addChild("input", new String[]{"type", "id", "name", "checked"}, new String[]{"checkbox", "hasAnActivelink", "hasAnActivelink", String.valueOf(item.hasAnActivelink())});
						else
							form.addChild("input", new String[]{"type", "id", "name"}, new String[]{"checkbox", "hasAnActivelink", "hasAnActivelink"});
						if(core.node.getDarknetConnections().length > 0 && ("addItem".equals(action) || "share".equals(action))) {
							form.addLineBreak();
							form.addLineBreak();
							Table peerTable = new Table(HTMLClass.DARKNETCONNECTIONS);
							form.addChild(peerTable);
							peerTable.addRow().addHeader(2, NodeL10n.getBase().getString("QueueToadlet.recommendToFriends"));
							for(DarknetPeerNode peer : core.node.getDarknetConnections()) {
								Row peerRow = peerTable.addRow(HTMLClass.DARKNETCONNECTIONSNORMAL);
								peerRow.addCell(HTMLClass.PEERMARKER).addChild("input", new String[]{"type", "name"}, new String[]{"checkbox", "node_" + peer.hashCode()});
								peerRow.addCell(HTMLClass.PEERNAME).addText(peer.getName());
							}
							form.addChild("label", "for", "descB", (NodeL10n.getBase().getString("BookmarkEditorToadlet.publicDescLabel") + ' '));
							form.addLineBreak();
							form.addChild("textarea", new String[]{"id", "name", "row", "cols"}, new String[]{"descB", "publicDescB", "3", "70"}, (isNew ? "" : item.getDescription()));
							form.addLineBreak();
						}
					}

					form.addChild("input", new String[]{"type", "name", "value"}, new String[]{"hidden", "bookmark", bookmarkPath});

					form.addChild("input", new String[]{"type", "name", "value"}, new String[]{"hidden", "action", req.getParam("action")});

					form.addChild("input", new String[]{"type", "value"}, new String[]{"submit", "share".equals(action) ? NodeL10n.getBase().getString("BookmarkEditorToadlet.share") : NodeL10n.getBase().getString("BookmarkEditorToadlet.save")});
				} else if("up".equals(action))
					bookmarkManager.moveBookmarkUp(bookmarkPath, true);
				else if("down".equals(action))
					bookmarkManager.moveBookmarkDown(bookmarkPath, true);
		}

		if (cutedPath != null) {
			InfoboxWidget confirmClipboard = new InfoboxWidget(InfoboxWidget.Type.NORMAL, NodeL10n.getBase().getString("BookmarkEditorToadlet.pasteTitle"));
			content.addChild(confirmClipboard);
			confirmClipboard.body.addText(NodeL10n.getBase().getString("BookmarkEditorToadlet.pasteOrCancel"));
			HTMLNode cancelForm = ctx.addFormChild(confirmClipboard.body, "/bookmarkEditor/", "cancelCutForm");
			cancelForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"submit", "cancelCut", NodeL10n.getBase().getString("BookmarkEditorToadlet.cancelCut")});
			cancelForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"hidden", "action", "cancelCut"});
		}


		InfoboxWidget BookmarkTitle = new InfoboxWidget(InfoboxWidget.Type.NORMAL, HTMLClass.BOOKMARKTITLE, NodeL10n.getBase().getString("BookmarkEditorToadlet.myBookmarksTitle"));
		content.addChild(BookmarkTitle);
		BookmarkTitle.body.addList(getBookmarksList());

		HTMLNode addDefaultBookmarksForm = ctx.addFormChild(content, "", "AddDefaultBookmarks");
		addDefaultBookmarksForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"submit", "AddDefaultBookmarks", NodeL10n.getBase().getString("BookmarkEditorToadlet.addDefaultBookmarks")});

		if(logDEBUG)
			Logger.debug(this, "Returning:\n"+pageNode.generate());
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	public void handleMethodPOST(URI uri, HTTPRequest req, ToadletContext ctx)
		throws ToadletContextClosedException, IOException {
		PageMaker pageMaker = ctx.getPageMaker();
		PageNode page = pageMaker.getPageNode(NodeL10n.getBase().getString("BookmarkEditorToadlet.title"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode content = page.content;

		String passwd = req.getPartAsStringFailsafe("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) {
			writePermanentRedirect(ctx, "Invalid", "");
			return;
		}

		if(req.isPartSet("AddDefaultBookmarks")) {
			bookmarkManager.reAddDefaultBookmarks();
			this.writeTemporaryRedirect(ctx, "Ok", "/");
			return;
		}

		String bookmarkPath = req.getPartAsStringFailsafe("bookmark", MAX_BOOKMARK_PATH_LENGTH);
		try {

			Bookmark bookmark;
			if(bookmarkPath.endsWith("/"))
				bookmark = bookmarkManager.getCategoryByPath(bookmarkPath);
			else
				bookmark = bookmarkManager.getItemByPath(bookmarkPath);
			if(bookmark == null && !req.isPartSet("cancelCut")) {
				content.addInfobox(InfoboxWidget.Type.ERROR, HTMLClass.BOOKMARKERROR, NodeL10n.getBase().getString("BookmarkEditorToadlet.error"))
					.addText(NodeL10n.getBase().getString("BookmarkEditorToadlet.bookmarkDoesNotExist", new String[]{"bookmark"}, new String[]{bookmarkPath}));
				this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			}


			String action = req.getPartAsStringFailsafe("action", MAX_ACTION_LENGTH);

			if (req.isPartSet("confirmdelete")) {
				bookmarkManager.removeBookmark(bookmarkPath);
				bookmarkManager.storeBookmarks();
				content.addInfobox(InfoboxWidget.Type.SUCCESS, HTMLClass.BOOKMARKSUCCESSFULDELETE, NodeL10n.getBase().getString("BookmarkEditorToadlet.deleteSucceededTitle"))
					.addBlockText(NodeL10n.getBase().getString("BookmarkEditorToadlet.deleteSucceeded"));

			} else if(req.isPartSet("cancelCut"))
				cutedPath = null;
			else if("edit".equals(action) || "addItem".equals(action) || "addCat".equals(action)) {

				String name = "unnamed";
				if(req.isPartSet("name"))
					name = req.getPartAsStringFailsafe("name", MAX_NAME_LENGTH);

				if("edit".equals(action)) {
					bookmarkManager.renameBookmark(bookmarkPath, name);
					boolean hasAnActivelink = req.isPartSet("hasAnActivelink");
					if(bookmark instanceof BookmarkItem) {
						BookmarkItem item = (BookmarkItem) bookmark;
						item.update(new FreenetURI(req.getPartAsStringFailsafe("key", MAX_KEY_LENGTH)), hasAnActivelink, req.getPartAsStringFailsafe("descB", MAX_KEY_LENGTH), req.getPartAsStringFailsafe("explain", MAX_EXPLANATION_LENGTH));
						sendBookmarkFeeds(req, item, req.getPartAsStringFailsafe("publicDescB", MAX_KEY_LENGTH));
					}
					bookmarkManager.storeBookmarks();

					content.addInfobox(InfoboxWidget.Type.SUCCESS, HTMLClass.BOOKMARKERROR, NodeL10n.getBase().getString("BookmarkEditorToadlet.changesSavedTitle")).
						addBlockText(NodeL10n.getBase().getString("BookmarkEditorToadlet.changesSaved"));

				} else if("addItem".equals(action) || "addCat".equals(action)) {

					Bookmark newBookmark = null;
					if("addItem".equals(action)) {
						FreenetURI key = new FreenetURI(req.getPartAsStringFailsafe("key", MAX_KEY_LENGTH));
						/* TODO:
						 * <nextgens> I suggest you implement a HTTPRequest.getBoolean(String name) using Fields.stringtobool
						 * <nextgens> HTTPRequest.getBoolean(String name, boolean default) even
						 * 
						 * - values as "on", "true", "yes" should be accepted.
						 */
						boolean hasAnActivelink = req.isPartSet("hasAnActivelink");
						if (name.contains("/")) {
							content.addInfobox(InfoboxWidget.Type.ERROR, HTMLClass.BOOKMARKERROR, NodeL10n.getBase().getString("BookmarkEditorToadlet.invalidNameTitle")).
								addText(NodeL10n.getBase().getString("BookmarkEditorToadlet.invalidName"));
						} else
							newBookmark = new BookmarkItem(key, name, req.getPartAsStringFailsafe("descB", MAX_KEY_LENGTH), req.getPartAsStringFailsafe("explain", MAX_EXPLANATION_LENGTH), hasAnActivelink, core.alerts);
					} else
						if (name.contains("/")) {
							content.addInfobox(InfoboxWidget.Type.ERROR, HTMLClass.BOOKMARKERROR, NodeL10n.getBase().getString("BookmarkEditorToadlet.invalidNameTitle")).
								addText(NodeL10n.getBase().getString("BookmarkEditorToadlet.invalidName"));
						} else
							newBookmark = new BookmarkCategory(name);
					
					if (newBookmark != null) {
						bookmarkManager.addBookmark(bookmarkPath, newBookmark);
						bookmarkManager.storeBookmarks();
						if(newBookmark instanceof BookmarkItem) {
							sendBookmarkFeeds(req, (BookmarkItem) newBookmark, req.getPartAsStringFailsafe("publicDescB", MAX_KEY_LENGTH));
						}
						content.addInfobox(InfoboxWidget.Type.SUCCESS, HTMLClass.BOOKMARKADDNEW, NodeL10n.getBase().getString("BookmarkEditorToadlet.addedNewBookmarkTitle")).
							addBlockText(NodeL10n.getBase().getString("BookmarkEditorToadlet.addedNewBookmark"));
					}
				}
			}
			else if ("share".equals(action)) {
				sendBookmarkFeeds(req, (BookmarkItem) bookmark, req.getPartAsStringFailsafe("publicDescB", MAX_KEY_LENGTH));
			}
		} catch (MalformedURLException mue) {
			content.addInfobox(InfoboxWidget.Type.ERROR, HTMLClass.BOOKMARKERROR, NodeL10n.getBase().getString("BookmarkEditorToadlet.invalidKeyTitle")).
				addText(NodeL10n.getBase().getString("BookmarkEditorToadlet.invalidKey"));
		}
		content.addInfobox(InfoboxWidget.Type.NORMAL, HTMLClass.BOOKMARKS, NodeL10n.getBase().getString("BookmarkEditorToadlet.myBookmarksTitle")).
			addList(getBookmarksList());
		HTMLNode addDefaultBookmarksForm = ctx.addFormChild(content, "", "AddDefaultBookmarks");
		addDefaultBookmarksForm.addChild("input", new String[]{"type", "name", "value"}, new String[]{"submit", "AddDefaultBookmarks", NodeL10n.getBase().getString("BookmarkEditorToadlet.addDefaultBookmarks")});
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	@Override
	public String path() {
		return "/bookmarkEditor/";
	}
}
