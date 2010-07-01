/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.jmeter.protocol.smtp.sampler.protocol;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.security.Security;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.jmeter.services.FileServer;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

/**
 * This class performs all tasks necessary to send a message (build message,
 * prepare connection, send message). Provides getter-/setter-methods for an
 * SmtpSampler-object to configure transport and message settings. The
 * send-mail-command itself is started by the SmtpSampler-object.
 */
public class SendMailCommand {

    // local vars
    private static final Logger logger = LoggingManager.getLoggerForClass();
    
    // Use the actual class so the name must be correct.
    private static final String TRUST_ALL_SOCKET_FACTORY = TrustAllSSLSocketFactory.class.getName();

    private boolean useSSL = false;
    private boolean useStartTLS = false;
    private boolean trustAllCerts = false;
    private boolean enforceStartTLS = false;
    private boolean startTLSSuccessful = false;
    private boolean sendEmlMessage = false;
    private String smtpServer;
    private String smtpPort;
    private String sender;
    private String emlMessage;
    private List<InternetAddress> receiverTo;
    private List<InternetAddress> receiverCC;
    private List<InternetAddress> receiverBCC;
    private HashMap<String, String> headers;
    private String subject = "";

    private boolean useAuthentication = false;
    private String username;
    private String password;

    private boolean useLocalTrustStore;
    private String trustStoreToUse;

    private List<File> attachments;

    private String mailBody;

    // needed to check starttls functionality
    private PrintStream debugOutStream;
    private BufferedReader debugReader;

    // case we are measuring real time of spedition
    private boolean synchronousMode;

    private Session session;
    private Message message;

    private StringBuffer serverResponse = new StringBuffer();

    /**
     * Standard-Constructor
     */
    public SendMailCommand() {
        headers = new HashMap<String, String>();
        attachments = new ArrayList<File>();
    }

    /**
     * Prepares message prior to be sent via execute()-method, i.e. sets
     * properties such as protocol, authentication, etc.
     *
     * @return Message-object to be sent to execute()-method
     * @throws MessagingException 
     * @throws IOException 
     */
    public Message prepareMessage() throws MessagingException, IOException {

        Properties props = new Properties();

        String protocol = getProtocol();

        // set properties using JAF
        props.put("mail." + protocol + ".host", smtpServer);
        props.put("mail." + protocol + ".port", smtpPort);
        props.put("mail." + protocol + ".auth", Boolean
                .toString(useAuthentication));
        // props.put("mail.debug","true");

        if (useStartTLS) {
            props.put("mail.smtp.starttls.enable", "true");
            //props.put("mail.debug", "true");
        }

        if (trustAllCerts && protocol.equalsIgnoreCase("smtps")) {
            props.setProperty("mail.smtps.socketFactory.class",
                    TRUST_ALL_SOCKET_FACTORY);
            props.setProperty("mail.smtps.socketFactory.fallback", "false");
        }

        session = Session.getInstance(props, null);

        if (sendEmlMessage) {
            message = new MimeMessage(session, new FileInputStream(emlMessage));
        } else {
            message = new MimeMessage(session);
            // handle body and attachments
            Multipart multipart = new MimeMultipart();
            BodyPart body = new MimeBodyPart();
            body.setText(mailBody);
            multipart.addBodyPart(body);

            for (File f : attachments) {
                BodyPart attach = new MimeBodyPart();
                attach.setFileName(f.getName());
                attach.setDataHandler(new DataHandler(new FileDataSource(f)));
                multipart.addBodyPart(attach);
            }

            message.setContent(multipart);
        }

        // set from field and subject
        if (null != sender) {
            message.setFrom(new InternetAddress(sender));
        }
        if (null != subject) {
            message.setSubject(subject);
        }

        if (receiverTo != null) {
            InternetAddress[] to = new InternetAddress[receiverTo.size()];
            receiverTo.toArray(to);
            message.setRecipients(Message.RecipientType.TO, to);
        }

        if (receiverCC != null) {

            InternetAddress[] cc = new InternetAddress[receiverCC.size()];
            receiverCC.toArray(cc);
            message.setRecipients(Message.RecipientType.CC, cc);
        }

        if (receiverBCC != null) {
            InternetAddress[] bcc = new InternetAddress[receiverBCC.size()];
            receiverBCC.toArray(bcc);
            message.setRecipients(Message.RecipientType.BCC, bcc);
        }

        for (String key : headers.keySet()) {
            message.setHeader(key, headers.get(key));
        }

        message.saveChanges();
        return message;
    }

    /**
     * Sends message to mailserver, including all necessary tasks. Contains 2
     * ugly hacks to ensure the use of StartTLS if needed (see comments "UGLY
     * HACK X") where logfiles are monitored
     *
     * @param message
     *            Message prior prepared by prepareMessage()
     * @throws MessagingException 
     * @throws IOException 
     * @throws InterruptedException 
     */
    public void execute(Message message) throws MessagingException, IOException, InterruptedException {

        // TODO change to use thread-safe method
/*
 * Reduce impact on other threads - don't clear the setting each time.
 * Won't work with samplers that use both settings of the option, but they won't work reliably anyway across threads.
 * 
 * With this change, the code should be thread-safe if the user does not select useLocalTrustStore.
 * 
 *         System.clearProperty("javax.net.ssl.trustStore");
 */

        if (useLocalTrustStore) {
            File truststore = new File(trustStoreToUse);
            logger.info("load local truststore - try to load truststore from: "+truststore.getAbsolutePath());
            if(!truststore.exists()){
                logger.info("load local truststore -Failed to load truststore from: "+truststore.getAbsolutePath());
                truststore = new File(FileServer.getFileServer().getBaseDir(), trustStoreToUse);
                logger.info("load local truststore -Attempting to read truststore from:  "+truststore.getAbsolutePath());
                if(!truststore.exists()){
                    logger.info("load local truststore -Failed to load truststore from: "+truststore.getAbsolutePath() + ". Local truststore not available, aborting execution.");
                    throw new IOException("Local truststore file not found. Also not available under : " + truststore.getAbsolutePath());
                }
            }
            logger.warn("Setting javax.net.ssl.trustStore - may affect the behaviour of other threads");
            System.setProperty("javax.net.ssl.trustStore", truststore.getAbsolutePath());
        }

        /*
         * UGLY HACK 1: redirect session-DebugOutput to ensure
         * StartTLS-Support
         */
        ByteArrayOutputStream debugOutputStream = new ByteArrayOutputStream();
        debugOutStream = new PrintStream(debugOutputStream);
        session.setDebugOut(debugOutStream);
        session.setDebug(true);

        Transport tr = session.getTransport(getProtocol());
        SynchronousTransportListener listener = null;

        if (synchronousMode) {
            listener = new SynchronousTransportListener();
            tr.addTransportListener(listener);
        }

        if (useAuthentication) {
            tr.connect(smtpServer, username, password);
        } else {
            tr.connect();
        }

        tr.sendMessage(message, message.getAllRecipients());

        if (synchronousMode) {
            listener.attend();
        }

        tr.close();
        logger.debug("transport closed");

        /*
         * UGLY HACK 2: read from redirected debug-output
         */
        debugOutStream.flush();
        debugReader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(debugOutputStream.toByteArray())));
        String line;
        int i = 0;
        while ((line = debugReader.readLine()) != null) {
            logger.debug("server line " + i + ": " + line);
            // unusable for the astf runs bom
            //serverResponse.append(line);
            //serverResponse.append("\n");
            if (line.matches(".*Ready to start TLS.*")) {
                if (useStartTLS && enforceStartTLS) {
                    startTLSSuccessful = true;
                }
            }
        }
        debugReader.close();
        debugOutStream.close();
        session.setDebugOut(System.out);
        if (useStartTLS && enforceStartTLS) {
            if (!startTLSSuccessful) {
                throw new MessagingException("StartTLS failed");
            }
        }

        logger.debug("message sent");
        return;
    }

    /**
     * Processes prepareMessage() and execute()
     *
     * @throws Exception
     */
    public void execute() throws Exception {
        execute(prepareMessage());
    }

    /**
     * Returns FQDN or IP of SMTP-server to be used to send message - standard
     * getter
     *
     * @return FQDN or IP of SMTP-server
     */
    public String getSmtpServer() {
        return smtpServer;
    }

    /**
     * Sets FQDN or IP of SMTP-server to be used to send message - to be called
     * by SmtpSampler-object
     *
     * @param smtpServer
     *            FQDN or IP of SMTP-server
     */
    public void setSmtpServer(String smtpServer) {
        this.smtpServer = smtpServer;
    }

    /**
     * Returns sender-address for current message - standard getter
     *
     * @return sender-address
     */
    public String getSender() {
        return sender;
    }

    /**
     * Sets the sender-address for the current message - to be called by
     * SmtpSampler-object
     *
     * @param sender
     *            Sender-address for current message
     */
    public void setSender(String sender) {
        this.sender = sender;
    }

    /**
     * Returns subject for current message - standard getter
     *
     * @return Subject of current message
     */
    public String getSubject() {
        return subject;
    }

    /**
     * Sets subject for current message - called by SmtpSampler-object
     *
     * @param subject
     *            Subject for message of current message
     */
    public void setSubject(String subject) {
        this.subject = subject;
    }

    /**
     * Returns username to authenticate at the mailserver - standard getter
     *
     * @return Username for mailserver
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets username to authenticate at the mailserver - to be called by
     * SmtpSampler-object
     *
     * @param username
     *            Username for mailserver
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Returns password to authenticate at the mailserver - standard getter
     *
     * @return Password for mailserver
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets password to authenticate at the mailserver - to be called by
     * SmtpSampler-object
     *
     * @param password
     *            Password for mailserver
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Sets receivers of current message ("to") - to be called by
     * SmtpSampler-object
     *
     * @param receiverTo
     *            List of receivers <InternetAddress>
     */
    public void setReceiverTo(List<InternetAddress> receiverTo) {
        this.receiverTo = receiverTo;
    }

    /**
     * Returns receivers of current message <InternetAddress> ("cc") - standard
     * getter
     *
     * @return List of receivers
     */
    public List<InternetAddress> getReceiverCC() {
        return receiverCC;
    }

    /**
     * Sets receivers of current message ("cc") - to be called by
     * SmtpSampler-object
     *
     * @param receiverCC
     *            List of receivers <InternetAddress>
     */
    public void setReceiverCC(List<InternetAddress> receiverCC) {
        this.receiverCC = receiverCC;
    }

    /**
     * Returns receivers of current message <InternetAddress> ("bcc") - standard
     * getter
     *
     * @return List of receivers
     */
    public List<InternetAddress> getReceiverBCC() {
        return receiverBCC;
    }

    /**
     * Sets receivers of current message ("bcc") - to be called by
     * SmtpSampler-object
     *
     * @param receiverBCC
     *            List of receivers <InternetAddress>
     */
    public void setReceiverBCC(List<InternetAddress> receiverBCC) {
        this.receiverBCC = receiverBCC;
    }

    /**
     * Returns if authentication is used to access the mailserver - standard
     * getter
     *
     * @return True if authentication is used to access mailserver
     */
    public boolean isUseAuthentication() {
        return useAuthentication;
    }

    /**
     * Sets if authentication should be used to access the mailserver - to be
     * called by SmtpSampler-object
     *
     * @param useAuthentication
     *            Should authentication be used to access mailserver?
     */
    public void setUseAuthentication(boolean useAuthentication) {
        this.useAuthentication = useAuthentication;
    }

    /**
     * Returns if SSL is used to send message - standard getter
     *
     * @return True if SSL is used to transmit message
     */
    public boolean getUseSSL() {
        return useSSL;
    }

    /**
     * Sets SSL to secure the delivery channel for the message - to be called by
     * SmtpSampler-object
     *
     * @param useSSL
     *            Should StartTLS be used to secure SMTP-connection?
     */
    public void setUseSSL(boolean useSSL) {
        this.useSSL = useSSL;
    }

    /**
     * Returns if StartTLS is used to transmit message - standard getter
     *
     * @return True if StartTLS is used to transmit message
     */
    public boolean getUseStartTLS() {
        return useStartTLS;
    }

    /**
     * Sets StartTLS to secure the delivery channel for the message - to be
     * called by SmtpSampler-object
     *
     * @param useStartTLS
     *            Should StartTLS be used to secure SMTP-connection?
     */
    public void setUseStartTLS(boolean useStartTLS) {
        this.useStartTLS = useStartTLS;
    }

    /**
     * Returns port to be used for SMTP-connection (standard 25 or 465) -
     * standard getter
     *
     * @return Port to be used for SMTP-connection
     */
    public String getSmtpPort() {
        return smtpPort;
    }

    /**
     * Sets port to be used for SMTP-connection (standard 25 or 465) - to be
     * called by SmtpSampler-object
     *
     * @param smtpPort
     *            Port to be used for SMTP-connection
     */
    public void setSmtpPort(String smtpPort) {
        this.smtpPort = smtpPort;
    }

    /**
     * Returns if sampler should trust all certificates - standard getter
     *
     * @return True if all Certificates are trusted
     */
    public boolean isTrustAllCerts() {
        return trustAllCerts;
    }

    /**
     * Determines if SMTP-sampler should trust all certificates, no matter what
     * CA - to be called by SmtpSampler-object
     *
     * @param trustAllCerts
     *            Should all certificates be trusted?
     */
    public void setTrustAllCerts(boolean trustAllCerts) {
        this.trustAllCerts = trustAllCerts;
    }

    /**
     * Instructs object to enforce StartTLS and not to fallback to plain
     * SMTP-connection - to be called by SmtpSampler-object
     *
     * @param enforceStartTLS
     *            Should StartTLS be enforced?
     */
    public void setEnforceStartTLS(boolean enforceStartTLS) {
        this.enforceStartTLS = enforceStartTLS;
    }

    /**
     * Returns if StartTLS is enforced to secure the connection, i.e. no
     * fallback is used (plain SMTP) - standard getter
     *
     * @return True if StartTLS is enforced
     */
    public boolean isEnforceStartTLS() {
        return enforceStartTLS;
    }

    /**
     * Returns headers for current message - standard getter
     *
     * @return HashMap of headers for current message
     */
    public HashMap<String, String> getHeaders() {
        return headers;
    }

    /**
     * Sets headers for current message
     *
     * @param headers
     *            HashMap of headers for current message
     */
    public void setHeaders(HashMap<String, String> headers) {
        this.headers = headers;
    }

    /**
     * Adds a header-part to current HashMap of headers - to be called by
     * SmtpSampler-object
     *
     * @param headerName
     *            Key for current header
     * @param headerValue
     *            Value for current header
     */
    public void addHeader(String headerName, String headerValue) {
        if (this.headers == null)
            this.headers = new HashMap<String, String>();
        this.headers.put(headerName, headerValue);
    }

    /**
     * Deletes all current headers in HashMap
     */
    public void clearHeaders() {
        if (this.headers == null)
            this.headers = new HashMap<String, String>();
        else
            this.headers.clear();
    }

    /**
     * Returns all attachment for current message - standard getter
     *
     * @return List of attachments for current message
     */
    public List<File> getAttachments() {
        return attachments;
    }

    /**
     * Adds attachments to current message
     *
     * @param attachments
     *            List of files to be added as attachments to current message
     */
    public void setAttachments(List<File> attachments) {
        this.attachments = attachments;
    }

    /**
     * Adds an attachment to current message - to be called by
     * SmtpSampler-object
     *
     * @param attachment
     *            File-object to be added as attachment to current message
     */
    public void addAttachment(File attachment) {
        this.attachments.add(attachment);
    }

    /**
     * Clear all attachments for current message
     */
    public void clearAttachments() {
        this.attachments.clear();
    }

    /**
     * Returns if synchronous-mode is used for current message (i.e. time for
     * delivery, ... is measured) - standard getter
     *
     * @return True if synchronous-mode is used
     */
    public boolean isSynchronousMode() {
        return synchronousMode;
    }

    /**
     * Sets the use of synchronous-mode (i.e. time for delivery, ... is
     * measured) - to be called by SmtpSampler-object
     *
     * @param synchronousMode
     *            Should synchronous-mode be used?
     */
    public void setSynchronousMode(boolean synchronousMode) {
        this.synchronousMode = synchronousMode;
    }

    /**
     * Returns which protocol should be used to transport message (smtps for
     * SSL-secured connections or smtp for plain SMTP / StartTLS)
     *
     * @return Protocol that is used to transport message
     */
    private String getProtocol() {
        return (useSSL) ? "smtps" : "smtp";
    }

    /**
     * Assigns the object to use a local truststore for SSL / StartTLS - to be
     * called by SmtpSampler-object
     *
     * @param useLocalTrustStore
     *            Should a local truststore be used?
     */
    public void setUseLocalTrustStore(boolean useLocalTrustStore) {
        this.useLocalTrustStore = useLocalTrustStore;
    }

    /**
     * Sets the path to the local truststore to be used for SSL / StartTLS - to
     * be called by SmtpSampler-object
     *
     * @param trustStoreToUse
     *            Path to local truststore
     */
    public void setTrustStoreToUse(String trustStoreToUse) {
        this.trustStoreToUse = trustStoreToUse;
    }

    public void setUseEmlMessage(boolean sendEmlMessage) {
        this.sendEmlMessage = sendEmlMessage;
    }

    /**
     * Sets eml-message to be sent
     *
     * @param emlMessage
     *            path to eml-message
     */
    public void setEmlMessage(String emlMessage) {
        this.emlMessage = emlMessage;
    }

    /**
     * Set the mail body.
     * 
     * @param body
     */
    public void setMailBody(String body){
        mailBody = body;
    }

    public StringBuffer getServerResponse() {
        return this.serverResponse;
    }
}