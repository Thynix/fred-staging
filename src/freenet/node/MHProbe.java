package freenet.node;

import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.io.comm.*;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * TODO: Should this be a listener for things not sent locally?
 * Instantiated for each outgoing probe; incoming probes are dealt with by one instance thereof.
 */
public class MHProbe implements ByteCounter {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(Logger.LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(Logger.LogLevel.DEBUG, this);
			}
		});
	}

	/**
	 * Listener for the different types of probe results.
	 */
	public interface Listener {
		/**
		 * Probe response has timed out.
		 */
		void onTimeout();

		/**
		 * Peer from which response is expected has disconnected.
		 */
		void onDisconnected();

		/**
		 * Identifier result.
		 * @param identifier identifier given by endpoint
		 */
		void onIdentifier(long identifier);

		/**
		 * Uptime result.
		 * @param sessionUptime session uptime in hours
		 * @param uptime48hour percentage uptime in the last 48 hours
		 */
		//void onUptime(long sessionUptime, double uptime48hour);

		/**
		 * Output bandwidth limit result.
		 * @param outputBandwidth output bandwidth limit in KiB/s.
		 */
		//void onOutputBandwidth(int outputBandwidth);

		/**
		 * Store size result.
		 * @param storeSize store size in GiB
		 */
		//void onStoreSize(int storeSize);

		/**
		 * Link length result.
		 * @param linkLengths endpoint's reported link lengths. These may be of limited precision or have
		 *                    random noise added.
		 */
		void onLinkLengths(Double[] linkLengths);
		//TODO: HTL, key response,
	}

	public enum ProbeType {
		IDENTIFIER,
		LINK_LENGTHS

		//TODO: Uncomment for numerical codes.
		/*private int code;
		private static final Map<Integer, ProbeType> lookup = new HashMap<Integer, ProbeType>();

		static {
			for(ProbeType s : EnumSet.allOf(ProbeType.class)) lookup.put(s.code(), s);
		}

		private ProbeType(int code) {
			this.code = code;
		}

		public int code() {
			return code;
		}

		public static ProbeType get(int code) {
			return lookup.get(code);
		}*/
	}

	/**
	 * Counts as probe request transfer.
	 * @param bytes Bytes received.
	 */
	@Override
	public void sentBytes(int bytes) {
		nodeStats.probeRequestCtr.sentBytes(bytes);
	}

	/**
	 * Counts as probe request transfer.
	 * @param bytes Bytes received.
	 */
	@Override
	public void receivedBytes(int bytes) {
		nodeStats.probeRequestCtr.receivedBytes(bytes);
	}

	/**
	 * No payload in probes.
	 * @param bytes Ignored.
	 */
	@Override
	public void sentPayload(int bytes) {}

	public static final short MAX_HTL = 50;
	/**
	 * In ms, per HTL.
	 */
	public static final int TIMEOUT_PER_HTL = 5000;

	//TODO: This is a terrible hack isn't it?
	public static final int MAX_ACCEPTED = 5;
	public static volatile int accepted = 0;

	private final PeerManager peerManager;
	private final RandomSource random;
	//TODO: Not needed until more types of probe requests are implemented.
	private final UptimeEstimator estimator;
	private final long startTime;
	private final SubConfig nodeConfig;
	private final MessageCore messageCore;
	private final NodeStats nodeStats;
	private final long swapIdentifier;

	//TODO: This seems to be suffering from too-many-arguments disease. Is it reasonable to pass in the entire node?
	//TODO: Or just give up and implement this in NodeDispatcher?
	public MHProbe(PeerManager peerManager, RandomSource random, UptimeEstimator estimator, long startTime,
	               SubConfig nodeConfig, MessageCore messageCore, NodeStats nodeStats, long swapIdentifier) {
		this.peerManager = peerManager;
		this.random = random;
		this.estimator = estimator;
		this.startTime = startTime;
		this.nodeConfig = nodeConfig;
		this.messageCore = messageCore;
		this.nodeStats = nodeStats;
		this.swapIdentifier = swapIdentifier;
	}

	/**
	 * Sends an outgoing probe request.
	 * @param htl htl for this outgoing probe: should be [1, MAX_HTL]
	 * @param listener Something which implements MHProbe.Listener and will be called with results.
	 * @see MHProbe.Listener
	 */
	public void start(final short htl, final long uid, final ProbeType type, final Listener listener) {
		Message request = DMT.createMHProbeRequest(htl, uid, type);
		request(request, null, new ResultListener(listener));
	}

	//TODO: Localization
	private final static String sourceDisconnect = "Previous step in probe chain no longer connected.";

	//TODO: Ideal is: recieving request passes on message and waits on response or timeout.
	//TODO:          also sending one waits on response or timeout...

	/**
	 * Same as its three-argument namesake, but responds to results by passing them on to source.
	 * @param message probe request, (possibly made by DMT.createMHProbeRequest) containing HTL (TODO: and
	 *                optionally key to fetch)
	 * @param source node from which the probe request was received. Used to relay back results.
	 */
	public void request(Message message, PeerNode source) {
		request(message, source, new ResultRelay(source));
	}

	/**
	 * Processes an incoming probe request.
	 * If the probe has a positive HTL, routes with MH correction and probabilistically decrements HTL.
	 * TODO: How to handle multiple response types in FCP?
	 * TODO: Sending node selects this. If disallowed - what? Return error?
	 * TODO: Probabilistic HTL decrement.
	 * If the probe comes to have an HTL of zero: (an incoming HTL of zero is taken to be one.)
	 *     TODO: key and tracer requests. Separate datastore?!
	 *     returns as node settings allow at random exactly one of:
	 *         -unique identifier (specific to probe requests and unrelated to UID) (TODO)
	 *         TODO: config options to disable
	 *          and uptime information: session, 48-hour, (TODO) 7-day - uptime estimator
	 *         -output bandwidth rounded to nearest KiB
	 *         -store size rounded to nearest GiB
	 *         -htl success rates for remote requests (TODO: hourly stats record)
	 *         -link lengths (TODO)
	 *
	 *     -If a key is provided, it may also be: (TODO)
	 *         -a normal fetch
	 *         -a tracer request
	 * @param message probe request, containing HTL (TODO: and optionally key to fetch)
	 * @param source node from which the probe request was received. Used to relay back results. If null, it is
	 *               considered to have been sent from the local node.
	 * @param callback callback for probe response
	 */
	public void request(final Message message, final PeerNode source, final AsyncMessageFilterCallback callback) {
		if (accepted >= MAX_ACCEPTED) {
			if (logDEBUG) Logger.debug(MHProbe.class, "Already accepted maximum number of probes; rejecting incoming.");
			return;
		}
		short htl = message.getShort(DMT.HTL);
		if (htl < 0) {
			if (logDEBUG) Logger.debug(MHProbe.class, "HTL cannot be negative.");
			return;
		} else if (htl == 0) {
			htl = 1;
		} else if (htl > MAX_HTL) {
			htl = MAX_HTL;
		}

		/*
		 * Route to a peer, using Metropolis-Hastings correction and ignoring backoff to get a more uniform
		 * endpoint distribution.
		 */
		if (htl > 0) {
			//Degree of the local node.
			int degree = degree();
			/*
			 * Loop until HTL runs out, in which case return a result, (by falling out of the loop) or until
			 * a peer suitable to hand the message off to is found, in which case the message is sent and
			 * the method returns. HTL is decremented beforehand so the request can be accepted locally,
			 * though it is decremented probabilistically.
			 */
			htl = probabilisticDecrement(htl);
			while (htl > 0) {
				PeerNode candidate;
				//Can't return a probe request if not connected to anyone.
				if (degree == 0) {
					if (logDEBUG) Logger.minor(MHProbe.class, "Aborting received probe request; no connections.");
					return;
				}
				try {
					candidate = peerManager.myPeers[random.nextInt(degree)];
				} catch (IndexOutOfBoundsException e) {
					if (logDEBUG) Logger.debug(MHProbe.class, "Peer count changed during candidate search.", e);
					degree = degree();
					htl = probabilisticDecrement(htl);
					continue;
				}
				//acceptProbability is the MH correction.
				double acceptProbability = (double)degree / candidate.getDegree();
				if (logDEBUG) Logger.debug(MHProbe.class, "acceptProbability is "+acceptProbability);
				if (random.nextDouble() < acceptProbability) {
					if (candidate.isConnected()) {
						MessageFilter result = MessageFilter.create().setSource(candidate).setType(DMT.MHProbeResult).setField(DMT.UID, message.getLong(DMT.UID)).setTimeout(htl * TIMEOUT_PER_HTL);
						message.set(DMT.HTL, htl);
						try {
							candidate.sendAsync(message, null, null);
							messageCore.addAsyncFilter(result, callback, this);
						} catch (NotConnectedException e) {
							if (logDEBUG) Logger.debug(MHProbe.class, "Peer became disconnected between check and send attempt.", e);
							continue;
						} catch (DisconnectedException e) {
							//TODO: This is confusing - it's async yet it would throw an exception while waiting?
							if (logDEBUG) Logger.debug(MHProbe.class, "Peer became disconnected while waiting for a response.", e);
							//TODO: Is it reasonable to re-send in this case?
							//continue;
							callback.onDisconnect(candidate);
						}
						return;
					}
				}
			}
		}

		/*
		 * HTL is zero: return a result.
		 */
		if (htl == 0) {
			Message result;
			//Probe message/exchange identifier
			final long identifier = message.getLong(DMT.UID);

			ProbeType type;
			try {
				type = ProbeType.valueOf(message.getString(DMT.TYPE));
			} catch (IllegalArgumentException e) {
				if (logDEBUG) Logger.debug(MHProbe.class, "Invalid probe type.", e);
				return;
			}
			switch (type) {
			//TODO: Would it be better to have more methods which accept only valid result sets?
			//TODO: Are types enough to differentiate such sets?
			case IDENTIFIER:
				result = DMT.createMHProbeResult(identifier, swapIdentifier, null, null, null, null, null);
				break;
				/*result = DMT.createMHProbeResult(message.getLong(DMT.UID), swapIdentifier,
				    estimator.getUptime(), System.currentTimeMillis() - startTime,
				    nodeConfig.getInt("outputBandwidthLimit"), nodeConfig.getInt("storeSize"), linkLengths);*/
			case LINK_LENGTHS:
				Double[] linkLengths = new Double[degree()];
				int i = 0;
				for (PeerNode peer : peerManager.connectedPeers) {
					linkLengths[i++] = Math.min(Math.abs(peer.getLocation() - peerManager.node.getLocation()),
						1.0 - Math.abs(peer.getLocation() - peerManager.node.getLocation()));
					//TODO: random noise or limit mantissa
				}
				result = DMT.createMHProbeResult(identifier, null, null, null, null, null, linkLengths);
				break;
			default:
				if (logDEBUG) Logger.debug(MHProbe.class, "UnImplemented probe result type \"" + type + "\".");
				return;
			}
			//Returning result to probe sent locally.
			if (source == null) {
				callback.onMatched(message);
				return;
			}
			try {
				source.sendAsync(result, null, null);
			} catch (NotConnectedException e) {
				if (logDEBUG) Logger.debug(MHProbe.class, "Previous step in chain is no longer connected.");
			}
		}
	}

	private short probabilisticDecrement(short htl) {
		//TODO: A mathematical function - say one that's 0.2 at 1 and goes up to 0.9 at MAX_HTL could be more
		//flexible and give smoother behavior, but would also be more complicated?
		if (htl == 1 && random.nextDouble() < 0.2) return 0;
		else if (random.nextDouble() < 0.9) return (short)(htl - 1);
		return htl;
	}

	/**
	 * @return number of peers the local node is connected to.
	 */
	private int degree() {
		return peerManager.connectedPeers.length;
	}

	/**
	 * Filter listener which determines the type of result and
	 */
	private class ResultListener implements AsyncMessageFilterCallback {

		private final Listener listener;

		public ResultListener(Listener listener) {
			this.listener = listener;
		}

		/**
		 * Parses provided message and calls appropriate MHProbe.Listener method for the type of result
		 * present,
		 * @param message result message
		 */
		@Override
		public void onMatched(Message message) {
			assert(accepted > 0);
			accepted--;
			if (message.isSet(DMT.IDENTIFIER)) {
				listener.onIdentifier(message.getLong(DMT.IDENTIFIER));
			/*} if (message.isSet(DMT.UPTIME_SESSION) && message.isSet(DMT.UPTIME_PERCENT_48H)) {
				listener.onUptime(message.getLong(DMT.UPTIME_SESSION), message.getDouble(DMT.UPTIME_PERCENT_48H));
			} else if (message.isSet(DMT.OUTPUT_BANDWIDTH_UPPER_LIMIT)) {
				listener.onOutputBandwidth(message.getInt(DMT.OUTPUT_BANDWIDTH_UPPER_LIMIT));
			} else if (message.isSet(DMT.STORE_SIZE)) {
				listener.onStoreSize(message.getInt(DMT.STORE_SIZE));*/
			} else if (message.isSet(DMT.LINK_LENGTHS)) {
				//TODO: What if cast fails?
				listener.onLinkLengths((Double[])message.getObject(DMT.LINK_LENGTHS));
			} else {
				//TODO: The rest of the result types.
				if (logDEBUG) Logger.debug(MHProbe.class, "Unknown probe result set.");
			}
		}

		@Override
		public void onTimeout() {
			assert(accepted > 0);
			accepted--;
			listener.onTimeout();
		}

		@Override
		public boolean shouldTimeout() {
			return true;
		}

		@Override
		public void onDisconnect(PeerContext context) {
			assert(accepted > 0);
			accepted--;
			listener.onDisconnected();
		}

		@Override
		public void onRestarted(PeerContext context) {}
	}

	/**
	 * Filter listener which relays messages onto the source given during construction. USed when receiving probes
	 * not sent locally.
	 */
	private class ResultRelay implements AsyncMessageFilterCallback {

		private final PeerNode source;

		/**
		 *
		 * @param source peer from which the request was received and to which send the response.
		 */
		public ResultRelay(PeerNode source) {
			if (source == null) {
				if (logDEBUG) Logger.debug(MHProbe.class, "Cannot return probe result to null peer.");
			}
			this.source = source;
		}


		/**
		 * Sends an incoming probe response to the originator.
		 * @param message probe response
		 */
		@Override
		public void onMatched(Message message) {
			assert(accepted > 0);
			accepted--;
			if (source == null) {
				if (logMINOR) Logger.minor(MHProbe.class, sourceDisconnect);
				return;
			}

			//TODO: If result is a tracer request, can add local results to it.
			try {
				source.sendAsync(message, null, null);
			} catch (NotConnectedException e) {
				if (logMINOR) Logger.minor(MHProbe.class, sourceDisconnect);
			}
		}

		@Override
		public void onTimeout() {
			assert(accepted > 0);
			accepted--;
		}

		//TODO: What does this mean? Its existence implies multiple levels of being timed-out.
		@Override
		public boolean shouldTimeout() {
			return true;
		}

		@Override
		public void onDisconnect(PeerContext context) {
			assert(accepted > 0);
			accepted--;
		}

		@Override
		public void onRestarted(PeerContext context) {}
	}
}
