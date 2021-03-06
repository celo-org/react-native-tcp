package com.peel.react;

import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import androidx.annotation.Nullable;

import com.koushikdutta.async.AsyncNetworkSocket;
import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.AsyncServerSocket;
import com.koushikdutta.async.AsyncSocket;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.Util;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.ConnectCallback;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.callback.ListenCallback;

import java.io.InputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;

import com.unixsocket.UnixSocketHandler;
import com.unixsocket.UnixSocketManager;

/**
 * Created by aprock on 12/29/15.
 */
public final class TcpSocketManager {
    private static final String TAG = "TcpSocketManager";

    private SparseArray<Object> mClients = new SparseArray<Object>();
    private SparseArray<Handler> mSocketReaders = new SparseArray<Handler>();

    private WeakReference<TcpSocketListener> mListener;
    private AsyncServer mServer = AsyncServer.getDefault();

    private UnixSocketManager mUnixSocketManager = new UnixSocketManager();

    private int mInstances = 5000;

    public TcpSocketManager(TcpSocketListener listener) throws IOException {
        mListener = new WeakReference<TcpSocketListener>(listener);
    }

    private void setSocketCallbacks(final Integer cId, final AsyncSocket socket) {
        socket.setClosedCallback(new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onClose(cId, ex==null?null:ex.getMessage());
                }
            }
        });

        socket.setDataCallback(new DataCallback() {
            @Override
            public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onData(cId, bb.getAllByteArray());
                }
            }
        });

        socket.setEndCallback(new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                if (ex != null) {
                    TcpSocketListener listener = mListener.get();
                    if (listener != null) {
                        listener.onError(cId, ex.getMessage());
                    }
                }
                socket.close();
            }
        });
    }

    public void listen(final Integer cId, final String host, final Integer port) throws UnknownHostException, IOException {
        // resolve the address
        final InetSocketAddress socketAddress;
        if (host != null) {
            socketAddress = new InetSocketAddress(InetAddress.getByName(host), port);
        } else {
            socketAddress = new InetSocketAddress(port);
        }

        mServer.listen(InetAddress.getByName(host), port, new ListenCallback() {
            @Override
            public void onListening(AsyncServerSocket socket) {
                mClients.put(cId, socket);

                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onConnect(cId, socketAddress);
                }
            }

            @Override
            public void onAccepted(AsyncSocket socket) {
                setSocketCallbacks(mInstances, socket);
                mClients.put(mInstances, socket);

                AsyncNetworkSocket socketConverted = Util.getWrappedSocket(socket, AsyncNetworkSocket.class);
                InetSocketAddress remoteAddress = socketConverted != null ? socketConverted.getRemoteAddress() : socketAddress;

                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onConnection(cId, mInstances, remoteAddress);
                }

                mInstances++;
            }

            @Override
            public void onCompleted(Exception ex) {
                mClients.delete(cId);

                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onClose(cId, ex != null ? ex.getMessage() : null);
                }
            }
        });
    }

    public void connect(final Integer cId, final @Nullable String host, final Integer port) throws UnknownHostException, IOException {
        // resolve the address
        final InetSocketAddress socketAddress;
        if (host != null) {
            socketAddress = new InetSocketAddress(InetAddress.getByName(host), port);
        } else {
            socketAddress = new InetSocketAddress(port);
        }

        mServer.connectSocket(socketAddress, new ConnectCallback() {
            @Override
            public void onConnectCompleted(Exception ex, AsyncSocket socket) {
              TcpSocketListener listener = mListener.get();
                if (ex == null) {
                    mClients.put(cId, socket);
                    setSocketCallbacks(cId, socket);

                    if (listener != null) {
                        listener.onConnect(cId, socketAddress);
                    }
                } else if (listener != null) {
                   listener.onError(cId, ex.getMessage());
                }
            }
        });
    }

    public void connectIPC(final Integer cId, final String path) throws IOException {
	UnixSocketManager.DataCallback onDataCallback =
	    new UnixSocketManager.DataCallback() {
		public void onDataAvailable(byte[] data) {
		    Log.d(TAG, "onDataAvailable: " + new String(data));
		    TcpSocketListener listener = mListener.get();
		    if (listener != null) {
			listener.onData(cId, data);
		    }
		}

		public void onError(String message) {
		    TcpSocketListener listener = mListener.get();
		    if (listener != null) {
			listener.onError(cId, message);
		    }
		}
	    };

	UnixSocketHandler handler = new UnixSocketHandler();
	mUnixSocketManager.connect(handler, path);
	mClients.put(cId, handler);
	mUnixSocketManager.setDataCallback(handler, onDataCallback);
	mUnixSocketManager.setupLoop();

	TcpSocketListener listener = mListener.get();
        if (listener != null) {
	    LocalSocketAddress socketAddress = new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM);
            listener.onConnect(cId, socketAddress);
        }
    }

    public void connectIPCSync(final Integer cId, final String path) throws IOException {
        // resolve the address
        LocalSocketAddress socketAddress = new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM);
        LocalSocket socket = new LocalSocket();
        socket.connect(socketAddress);
        mClients.put(cId, socket);
        TcpSocketListener listener = mListener.get();
        if (listener != null) {
            listener.onConnect(cId, socketAddress);
        }

        Handler handler = new Handler(Looper.getMainLooper());
        mSocketReaders.put(cId, handler);

        final int delay = 500;
        handler.postDelayed(new Runnable(){
            public void run(){
                try {
                    Object socket = mClients.get(cId);
                    if (socket != null && socket instanceof LocalSocket) {
                        LocalSocket localSocket = (LocalSocket)socket;
                        InputStream inputStream = localSocket.getInputStream();
                        while (inputStream.available() > 0) {
                            byte[] targetArray = new byte[inputStream.available()];
                            inputStream.read(targetArray);
                            TcpSocketListener listener = mListener.get();
                            if (listener != null) {
                                listener.onData(cId, targetArray);
                            }
                        }
                    }
                } catch (IOException e) {
                    TcpSocketListener listener = mListener.get();
                    if (listener != null) {
                        listener.onError(cId, e.getMessage());
                    }
                }

                Handler handler = mSocketReaders.get(cId);
                handler.postDelayed(this, delay);
            }
        }, delay);
    }

    public void write(final Integer cId, final byte[] data) {
        Object socket = mClients.get(cId);
        if (socket != null && socket instanceof AsyncSocket) {
            ((AsyncSocket) socket).write(new ByteBufferList(data));
        } else if (socket != null && socket instanceof LocalSocket) {
            try {
                ((LocalSocket) socket).getOutputStream().write(data);
            } catch (IOException e) {
                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onError(cId, e.getMessage());
                }
            }
        } else if (socket != null && socket instanceof UnixSocketHandler) {
	    try {
		UnixSocketHandler handler = (UnixSocketHandler) socket;
		handler.write(data);
	    } catch (IOException e) {
                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onError(cId, e.getMessage());
		}
            }
	}
    }

    public void close(final Integer cId) {
        Object socket = mClients.get(cId);
        if (socket != null) {
            if (socket instanceof AsyncSocket) {
                ((AsyncSocket) socket).close();
            } else if (socket instanceof AsyncServerSocket) {
                ((AsyncServerSocket) socket).stop();
            } else if (socket instanceof LocalSocket) {
                try {
                    ((LocalSocket) socket).getOutputStream().close();
                    ((LocalSocket) socket).close();

                    Handler handler = mSocketReaders.get(cId);
                    if (handler != null) {
                        handler.removeCallbacksAndMessages(null);
                        mSocketReaders.remove(cId);
                    }

                    TcpSocketListener listener = mListener.get();
                    if (listener != null) {
                        listener.onClose(cId, null);
                    }
                } catch (IOException e) {
                    TcpSocketListener listener = mListener.get();
                    if (listener != null) {
                        listener.onError(cId, e.getMessage());
                    }
                }
            } else if (socket instanceof UnixSocketHandler) {
		try {
		    UnixSocketHandler handler = (UnixSocketHandler) socket;
		    handler.close();

		    TcpSocketListener listener = mListener.get();
		    if (listener != null) {
			listener.onClose(cId, null);
		    }

		    // TODO(erdal): remove from mClients?
		} catch (IOException e) {
		    TcpSocketListener listener = mListener.get();
                    if (listener != null) {
                        listener.onError(cId, e.getMessage());
                    }
		}
	    }
        } else {
            TcpSocketListener listener = mListener.get();
            if (listener != null) {
               listener.onError(cId, "unable to find socket");
            }
        }
    }

    public void closeAllSockets() {
        for (int i = 0; i < mClients.size(); i++) {
            close(mClients.keyAt(i));
        }
        mClients.clear();
    }
}
