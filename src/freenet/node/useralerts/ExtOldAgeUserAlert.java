package freenet.node.useralerts;

import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.htmlPrimitives.HTMLCLASS;
import freenet.support.htmlPrimitives.Div;

public class ExtOldAgeUserAlert extends AbstractUserAlert {
	
	/**
	 * Creates a new alert.
	 */
	public ExtOldAgeUserAlert() {
		super(true, null, null, null, null, UserAlert.ERROR, true, NodeL10n.getBase().getString("UserAlert.hide"), true, null);
	}
	
	@Override
	public String getTitle() {
		return l10n("extTooOldTitle");
	}
	
	@Override
	public String getText() {
		return l10n("extTooOld");
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("ExtOldAgeUserAlert."+key);
	}

	@Override
	public HTMLNode getHTMLText() {
		return new Div(HTMLCLASS.NONE, getText());
	}

	@Override
	public String getShortText() {
		return l10n("extTooOldShort");
	}

}