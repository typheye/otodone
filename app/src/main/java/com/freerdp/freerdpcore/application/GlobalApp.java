/*
   Minimal Android FreeRDP session registry.

   Vendored from the official FreeRDP Android client and trimmed down to the
   pieces the JNI bridge actually needs:
     * a static session map consulted by LibFreeRDP callbacks
     * per-instance connection event delivery on the main thread
   The original class extended Application, dragged in appcompat, preference,
   printing and the screen receiver; none of that is required for the JNI
   contract, so it was removed. Static method names used by LibFreeRDP are kept.
*/

package com.freerdp.freerdpcore.application;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.services.LibFreeRDP;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class GlobalApp implements LibFreeRDP.EventListener
{
	private static final Map<Long, SessionState> sessionMap =
	    Collections.synchronizedMap(new HashMap<Long, SessionState>());
	private static final Map<Long, SessionEventListener> sessionListeners =
	    new ConcurrentHashMap<>();
	private static final Handler mainHandler = new Handler(Looper.getMainLooper());
	private static final GlobalApp INSTANCE = new GlobalApp();
	private static boolean installed = false;

	/** Per-session connection event listener. Callbacks are invoked on the main thread. */
	public interface SessionEventListener
	{
		void onConnectionSuccess();
		void onConnectionFailure();
		void onDisconnected();
	}

	/** Registers [INSTANCE] as the global LibFreeRDP event listener (idempotent). */
	public static synchronized void install()
	{
		if (!installed)
		{
			LibFreeRDP.setEventListener(INSTANCE);
			installed = true;
		}
	}

	public static void registerSessionListener(long instance, SessionEventListener listener)
	{
		sessionListeners.put(instance, listener);
	}

	public static void unregisterSessionListener(long instance)
	{
		sessionListeners.remove(instance);
	}

	private static void dispatch(long instance, Consumer<SessionEventListener> action)
	{
		final SessionEventListener listener = sessionListeners.get(instance);
		if (listener == null)
			return;
		mainHandler.post(() -> action.accept(listener));
	}

	public static SessionState createSession(BookmarkBase bookmark, Context context)
	{
		SessionState session = new SessionState(LibFreeRDP.newInstance(context), bookmark);
		sessionMap.put(session.getInstance(), session);
		return session;
	}

	public static SessionState createSession(Uri openUri, Context context)
	{
		SessionState session = new SessionState(LibFreeRDP.newInstance(context), openUri);
		sessionMap.put(session.getInstance(), session);
		return session;
	}

	public static SessionState getSession(long instance)
	{
		return sessionMap.get(instance);
	}

	public static Collection<SessionState> getSessions()
	{
		return new ArrayList<>(sessionMap.values());
	}

	public static void freeSession(long instance)
	{
		sessionMap.remove(instance);
		LibFreeRDP.freeInstance(instance);
	}

	@Override public void OnPreConnect(long instance)
	{
	}

	@Override public void OnConnectionSuccess(long instance)
	{
		dispatch(instance, SessionEventListener::onConnectionSuccess);
	}

	@Override public void OnConnectionFailure(long instance)
	{
		dispatch(instance, SessionEventListener::onConnectionFailure);
	}

	@Override public void OnDisconnecting(long instance)
	{
	}

	@Override public void OnDisconnected(long instance)
	{
		dispatch(instance, SessionEventListener::onDisconnected);
	}
}
