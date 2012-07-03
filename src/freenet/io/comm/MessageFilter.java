/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package freenet.io.comm;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import freenet.node.PrioRunnable;
import freenet.support.Executor;
import freenet.support.Logger;

/**
 * @author ian
 *
 * To change the template for this generated type comment go to Window - Preferences - Java - Code Generation - Code and
 * Comments
 */
public final class MessageFilter {

	private static volatile boolean logMINOR;

	static {
		Logger.registerClass(MessageFilter.class);
	}

	public static final String VERSION = "$Id: MessageFilter.java,v 1.7 2005/08/25 17:28:19 amphibian Exp $";

	private boolean _matched;
	private PeerContext _droppedConnection;
	private MessageType _type;
	private final HashMap<String, Object> _fields = new HashMap<String, Object>();
	private final List<String> _fieldList = new LinkedList<String>();
	private PeerContext _source;
	private long _timeout;
	/** If true, timeouts are relative to the start of waiting, if false, they are relative to
	 * the time of calling setTimeout() */
	private boolean _timeoutFromWait;
	private int _initialTimeout;
	private MessageFilter _or;
	private Message _message;
	private long _oldBootID;
	private AsyncMessageFilterCallback _callback;
	private ByteCounter _ctr;
	private boolean _setTimeout = false;

	private MessageFilter() {
		_timeoutFromWait = true;
	}

	public static MessageFilter create() {
		return new MessageFilter();
	}

	void onStartWaiting(boolean waitFor) {
		synchronized(this) {
			/* We cannot wait on a MessageFilter with a callback, because onMatched() calls clearMatched()
			 * if we have a callback. The solution would be to:
			 * - Set a flag indicating we are waitFor()ing a filter here.
			 * - On matching a message (setMessage), call the callback immediately if not waitFor()ing.
			 * - If we are waitFor()ing, call the callback when we exit waitFor() (onStopWaiting()???).
			 */
			if(waitFor && _callback != null)
				throw new IllegalStateException("Cannot wait on a MessageFilter with a callback!");
			if(!_setTimeout)
				throw new IllegalStateException("No timeout set on filter " + this + "; cannot wait.");
			if(_initialTimeout > 0 && _timeoutFromWait)
				_timeout = System.currentTimeMillis() + _initialTimeout;
			if(_or != null)
				_or.onStartWaiting(waitFor);
		}
	}

	/**
	 * Set whether the timeout is relative to the creation of the filter, or the start of
	 * waitFor().
	 * @param b If true, the timeout is relative to the time at which setTimeout() was called,
	 * if false, it's relative to the start of waitFor().
	 */
	public synchronized MessageFilter setTimeoutRelativeToCreation(boolean b) {
		_timeoutFromWait = !b;
		return this;
	}

	/**
	 * This filter will expire after the specificed amount of time. Note also that where two or more filters match the
	 * same message, the one with the nearer expiry time will get priority
	 *
	 * @param timeout The time before this filter expires in ms
	 * @return This message filter
	 */
	public MessageFilter setTimeout(int timeout) {
		long currentTime = System.currentTimeMillis();
		synchronized (this) {
			_setTimeout = true;
			_initialTimeout = timeout;
			_timeout = currentTime + timeout;
		}
		return this;
	}

	public synchronized MessageFilter setNoTimeout() {
		_setTimeout = true;
		_timeout = Long.MAX_VALUE;
		_initialTimeout = 0;
		return this;
	}

	public synchronized MessageFilter setType(MessageType type) {
		_type = type;
		return this;
	}

	public synchronized MessageFilter setSource(PeerContext source) {
		_source = source;
		if(source != null)
			_oldBootID = source.getBootID();
		return this;
	}

	/**
	 Returns the source that this filter (or chain) matches
	 */
	public synchronized PeerContext getSource() {
		return _source;
	}

	public MessageFilter setField(String fieldName, boolean value) {
		return setField(fieldName, Boolean.valueOf(value));
	}

	public MessageFilter setField(String fieldName, byte value) {
		return setField(fieldName, Byte.valueOf(value));
	}

	public MessageFilter setField(String fieldName, short value) {
		return setField(fieldName, Short.valueOf(value));
	}

	public MessageFilter setField(String fieldName, int value) {
		return setField(fieldName, Integer.valueOf(value));
	}

	public MessageFilter setField(String fieldName, long value) {
		return setField(fieldName, Long.valueOf(value));
	}

	public MessageFilter setField(String fieldName, Object fieldValue) {
		synchronized (this) {
			if ((_type != null) && (!_type.checkType(fieldName, fieldValue))) {
				throw new IncorrectTypeException("Got " + fieldValue.getClass() + ", expected " + _type.typeOf(fieldName) + " for " + _type.getName());
			}
			if(_fields.put(fieldName, fieldValue) == null)
				_fieldList.add(fieldName);
		}
		return this;
	}

	public synchronized MessageFilter or(MessageFilter or) {
		if((or != null) && (_or != null) && or != _or) {
			throw new IllegalStateException("Setting a second .or() on the same filter will replace the " +
				"existing one, not add another. " + _or + " would be replaced by " + or + ".");
		}
		_or = or;
		return this;
	}

	public synchronized MessageFilter setAsyncCallback(AsyncMessageFilterCallback cb, ByteCounter ctr) {
		_callback = cb;
		_ctr = ctr;
		return this;
	}

	enum MATCHED {
		MATCHED,
		TIMED_OUT,
		TIMED_OUT_AND_MATCHED,
		NONE
	}

	public MATCHED match(Message m, long now) {
		return match(m, false, now);
	}

	public MATCHED match(Message m, boolean noTimeout, long now) {
		synchronized (this) {
			if(_or != null) {
				MATCHED matched = _or.match(m, noTimeout, now);
				if(matched != MATCHED.NONE)
					return matched; // Filter is matched once only. That includes timeouts.
			}

			if ((_type != null) && (!_type.equals(m.getSpec()))) {
				// Timeout immediately, but don't check the callback, so we still need the periodic check.
				if(_timeout < now)
					return MATCHED.TIMED_OUT;
				return MATCHED.NONE;
			}
			if ((_source != null) && (!_source.equals(m.getSource()))) {
				if(_timeout < now)
					return MATCHED.TIMED_OUT;
				return MATCHED.NONE;
			}
			for (String fieldName : _fieldList) {
				if (!m.isSet(fieldName)) {
					if(_timeout < now)
						return MATCHED.TIMED_OUT;
					return MATCHED.NONE;
				}
				if (!_fields.get(fieldName).equals(m.getFromPayload(fieldName))) {
					if(_timeout < now)
						return MATCHED.TIMED_OUT;
					return MATCHED.NONE;
				}
			}
		}
		if((!noTimeout) && reallyTimedOut(now)) {
			if(logMINOR) Logger.minor(this, "Matched but timed out: "+this);
			return MATCHED.TIMED_OUT_AND_MATCHED;
		}
		return MATCHED.MATCHED;
	}

	public synchronized boolean matched() {
		return _matched;
	}

	/**
	 * Which connection dropped or was restarted?
	 */
	public synchronized PeerContext droppedConnection() {
		return _droppedConnection;
	}

	boolean reallyTimedOut(long time) {
		AsyncMessageFilterCallback callback;
		boolean timedOut;
		synchronized (this) {
			callback = _callback;
			timedOut = _timeout < time;
		}
		// Avoid calling callback unless not otherwise timed out.
		if(!timedOut && callback != null && callback.shouldTimeout()) {
			synchronized (this) {
				_timeout = -1; // timeout immediately
			}
			return true;
		}
		return timedOut;
	}

	/**
	 * @param time The current time in milliseconds.
	 * @return True if the filter has timed out, or if it has been matched already. Caller will
	 * remove the filter from _filters if we return true.
	 */
	boolean timedOut(long time) {
		boolean matched;
		synchronized (this) {
			matched = _matched;
		}
		if(matched) {
			Logger.error(this, "Impossible: filter already matched in timedOut(): "+this, new Exception("error"));
			return true; // Remove it.
		}
		return reallyTimedOut(time);
	}

	public synchronized Message getMessage() {
		return _message;
	}

	public synchronized void setMessage(Message message) {
		//Logger.debug(this, "setMessage("+message+") on "+this, new Exception("debug"));
		_message = message;
		// Avoid race conditions where it is removed from the filter list because of a timeout but not woken up.
		_matched = true;
		notifyAll();
	}

	public synchronized int getInitialTimeout() {
		return _initialTimeout;
	}

	public synchronized long getTimeout() {
		return _timeout;
	}

	@Override
	public String toString() {
		MessageType type;
		synchronized (this) {
			type = _type;
		}
		return super.toString()+":" + (type == null ? "" : type.getName());
	}

	public void clearMatched() {
		// If the filter matched in an _or, and it is re-used, then
		// we need to clear all the _or's.
		MessageFilter or;
		synchronized(this) {
			_matched = false;
			_message = null;
			or = _or;
		}
		if(or != null)
			or.clearMatched();
	}

	public synchronized void clearOr() {
		_or = null;
	}

	public synchronized boolean matchesDroppedConnection(PeerContext ctx) {
		return _source == ctx || (_or != null && _or.matchesDroppedConnection(ctx));
	}

	public synchronized boolean matchesRestartedConnection(PeerContext ctx) {
		return _source == ctx || (_or != null && _or.matchesRestartedConnection(ctx));
	}

	/**
	 * Notify because of a dropped connection.
	 * Caller must verify _matchesDroppedConnection and _source.
	 * @param ctx
	 */
	public void onDroppedConnection(final PeerContext ctx, Executor executor) {
		final AsyncMessageFilterCallback cb;
		synchronized(this) {
			cb = _callback;
			_droppedConnection = ctx;
			notifyAll();
			_ctr = null;
		}
		if(cb != null) {
			if(cb instanceof SlowAsyncMessageFilterCallback) {
				executor.execute(new PrioRunnable() {
					@Override
					public void run() {
						cb.onDisconnect(ctx);
					}

					@Override
					public int getPriority() {
						return ((SlowAsyncMessageFilterCallback)cb).getPriority();
					}
				});
			} else {
				cb.onDisconnect(ctx);
			}
		}
	}

	/**
	 * Notify because of a restarted connection.
	 * Caller must verify _matchesDroppedConnection and _source.
	 * @param ctx
	 */
	public void onRestartedConnection(final PeerContext ctx, Executor executor) {
		final AsyncMessageFilterCallback cb;
		synchronized(this) {
			_droppedConnection = ctx;
			cb = _callback;
			notifyAll();
			_ctr = null;
		}
		if(cb != null) {
			if(cb instanceof SlowAsyncMessageFilterCallback) {
				executor.execute(new PrioRunnable() {
					@Override
					public void run() {
						cb.onRestarted(ctx);
					}

					@Override
					public int getPriority() {
						return ((SlowAsyncMessageFilterCallback)cb).getPriority();
					}
				});
			} else {
				cb.onRestarted(ctx);
			}
		}
	}

	/**
	 * Notify waiters that we have been matched.
	 * Hopefully no locks will be held at this point by the caller.
	 */
	public void onMatched(Executor executor) {
		final Message msg;
		final AsyncMessageFilterCallback cb;
		ByteCounter ctr;
		synchronized(this) {
			msg = _message;
			cb = _callback;
			ctr = _ctr;
			// Clear matched before calling callback in case we are re-added.
			if(_callback != null)
				clearMatched();
		}
		if(cb != null) {
			if(cb instanceof SlowAsyncMessageFilterCallback)
				executor.execute(new PrioRunnable() {
					@Override
					public void run() {
						cb.onMatched(msg);
					}

					@Override
					public int getPriority() {
						return ((SlowAsyncMessageFilterCallback)cb).getPriority();
					}
				}, "Slow callback for "+cb);
			else
				cb.onMatched(msg);
			if(ctr != null)
				ctr.receivedBytes(msg._receivedByteCount);
		}
	}

	/**
	 * Notify waiters that we have timed out.
	 */
	public void onTimedOut(Executor executor) {
		final AsyncMessageFilterCallback cb;
		synchronized(this) {
			notifyAll();
			cb = _callback;
		}
		if(cb != null) {
			if(cb instanceof SlowAsyncMessageFilterCallback) {
				executor.execute(new PrioRunnable() {
					@Override
					public void run() {
						cb.onTimeout();
					}

					@Override
					public int getPriority() {
						return ((SlowAsyncMessageFilterCallback)cb).getPriority();
					}
				});
			} else
				cb.onTimeout();
		}
	}

	/**
	 * Returns true if a connection related to this filter has been dropped or restarted.
	 */
	public synchronized boolean anyConnectionsDropped() {
		if(_matched) return false;
		if(_source != null) {
			if(!_source.isConnected()) {
				return true;
			} else if(_source.getBootID() != _oldBootID) {
				return true; // Counts as a disconnect.
			}
		}
		return _or != null && _or.anyConnectionsDropped();
	}

	public synchronized boolean hasCallback() {
		return _callback != null;
	}
}
