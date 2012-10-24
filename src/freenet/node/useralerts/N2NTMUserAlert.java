/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import freenet.clients.http.uielements.Box;
import freenet.l10n.NodeL10n;
import freenet.node.DarknetPeerNode;
import freenet.node.PeerNode;
import freenet.node.fcp.FCPMessage;
import freenet.node.fcp.TextFeedMessage;
import freenet.support.HTMLNode;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.Date;

// Node To Node Text Message User Alert
public class N2NTMUserAlert extends AbstractUserAlert {
	private final WeakReference<PeerNode> peerRef;
	private final String messageText;
	private final int fileNumber;
	private final long composedTime;
	private final long sentTime;
	private final long receivedTime;
	private String sourceNodeName;
	private String sourcePeer;

	public N2NTMUserAlert(DarknetPeerNode sourcePeerNode,
			String message, int fileNumber, long composedTime, long sentTime,
			long receivedTime) {
		super(true, null, null, null, null, UserAlert.MINOR, true, null, true, null);
		this.messageText = message;
		this.fileNumber = fileNumber;
		this.composedTime = composedTime;
		this.sentTime = sentTime;
		this.receivedTime = receivedTime;
		peerRef = sourcePeerNode.getWeakRef();
		sourceNodeName = sourcePeerNode.getName();
		sourcePeer = sourcePeerNode.getPeer().toString();
	}

	@Override
	public String getTitle() {
		return l10n("title", new String[] { "number", "peername", "peer" },
				new String[] { Integer.toString(fileNumber),
						sourceNodeName,
						sourcePeer });
	}

	@Override
	public String getText() {
		return l10n("header", new String[] { "from", "composed", "sent",
				"received" }, new String[] { sourceNodeName,
				DateFormat.getInstance().format(new Date(composedTime)),
				DateFormat.getInstance().format(new Date(sentTime)),
				DateFormat.getInstance().format(new Date(receivedTime)) })
				+ ": " + messageText;
	}

	@Override
	public String getShortText() {
		return l10n("headerShort", "from", sourceNodeName);
	}

	@Override
	public HTMLNode getHTMLText() {
		Box alertNode = new Box();
		alertNode.addBlockText( l10n("header", new String[] { "from",
				"composed", "sent", "received" }, new String[] {
				sourceNodeName,
				DateFormat.getInstance().format(new Date(composedTime)),
				DateFormat.getInstance().format(new Date(sentTime)),
				DateFormat.getInstance().format(new Date(receivedTime)) }));
		String[] lines = messageText.split("\n");
		for (int i = 0, c = lines.length; i < c; i++) {
			alertNode.addText(lines[i]);
			if (i != lines.length - 1)
				alertNode.addLineBreak();
		}
		
		DarknetPeerNode pn = (DarknetPeerNode) peerRef.get();
		if(pn != null)
			alertNode.addBlockText().addLink("/send_n2ntm/?peernode_hashcode=" + pn.hashCode(), l10n("reply"));
		return alertNode;
	}

	@Override
	public String dismissButtonText() {
		return l10n("delete");
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("N2NTMUserAlert." + key);
	}

	private String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("N2NTMUserAlert." + key, patterns, values);
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("N2NTMUserAlert." + key, pattern, value);
	}

	@Override
	public void onDismiss() {
		DarknetPeerNode pn = (DarknetPeerNode) peerRef.get();
		if(pn != null)
			pn.deleteExtraPeerDataFile(fileNumber);
	}

	@Override
	public FCPMessage getFCPMessage() {
		return new TextFeedMessage(getTitle(),
				getShortText(), getText(), getPriorityClass(), getUpdatedTime(), sourceNodeName,
				composedTime, sentTime, receivedTime, messageText);
	}

	@Override
	public boolean isValid() {
		DarknetPeerNode pn = (DarknetPeerNode) peerRef.get();
		if(pn != null) {
			sourceNodeName = pn.getName();
			sourcePeer = pn.getPeer().toString();
		}
		return true;
	}

}
