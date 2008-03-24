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

package org.apache.jmeter.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.save.CSVSaveService;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.jorphan.util.JMeterStopThreadException;
import org.apache.jorphan.util.JOrphanUtils;
import org.apache.log.Logger;

public class CSVDataSet extends ConfigTestElement implements TestBean, LoopIterationListener {
	private static final Logger log = LoggingManager.getLoggerForClass();

	private static final long serialVersionUID = 2;

    private static final String EOFVALUE = // value to return at EOF 
        JMeterUtils.getPropDefault("csvdataset.eofstring", "<EOF>"); //$NON-NLS-1$ //$NON-NLS-2$

    private transient String filename;

    private transient String fileEncoding;

    private transient String variableNames;

    private transient String delimiter;

    private transient boolean quoted = false;
    
    private transient boolean recycle = true;
    
    private transient boolean stopThread = false;

    transient private String[] vars;

    private Object readResolve(){
        recycle = true;
        return this;
    }
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.jmeter.engine.event.LoopIterationListener#iterationStart(org.apache.jmeter.engine.event.LoopIterationEvent)
	 */
	public void iterationStart(LoopIterationEvent iterEvent) {
		FileServer server = FileServer.getFileServer();
		String _fileName = getFilename();
		if (vars == null) {
			server.reserveFile(_fileName, getFileEncoding());
			vars = JOrphanUtils.split(getVariableNames(), ","); // $NON-NLS-1$
		}
		try {
			String delim = getDelimiter();
			if (delim.equals("\\t")) // $NON-NLS-1$
				delim = "\t";// Make it easier to enter a Tab // $NON-NLS-1$
            JMeterVariables threadVars = this.getThreadContext().getVariables();
			String line = server.readLine(_fileName,getRecycle());
            if (line!=null) {// i.e. not EOF
                String[] lineValues = getQuotedData() ? 
                        CSVSaveService.csvReadFile(new BufferedReader(new StringReader(line)), delim.charAt(0))
                        : JOrphanUtils.split(line, delim, false);
    			for (int a = 0; a < vars.length && a < lineValues.length; a++) {
    				threadVars.put(vars[a], lineValues[a]);
    			}
    			// TODO - report unused columns?
    			// TODO - provide option to set unused variables ?
            } else {
            	if (getStopThread()) {
            		throw new JMeterStopThreadException("End of file detected");
            	}
                for (int a = 0; a < vars.length ; a++) {
                    threadVars.put(vars[a], EOFVALUE);
                }
            }
		} catch (IOException e) {// TODO - should the error be indicated in the variables?
			log.error(e.toString());
		}
	}

	/**
	 * @return Returns the filename.
	 */
	public String getFilename() {
		return filename;
	}

	/**
	 * @param filename
	 *            The filename to set.
	 */
	public void setFilename(String filename) {
		this.filename = filename;
	}

	/**
	 * @return Returns the file encoding.
	 */
	public String getFileEncoding() {
		return fileEncoding;
	}

	/**
	 * @param fileEncoding
	 *            The fileEncoding to set.
	 */
	public void setFileEncoding(String fileEncoding) {
		this.fileEncoding = fileEncoding;
	}

	/**
	 * @return Returns the variableNames.
	 */
	public String getVariableNames() {
		return variableNames;
	}

	/**
	 * @param variableNames
	 *            The variableNames to set.
	 */
	public void setVariableNames(String variableNames) {
		this.variableNames = variableNames;
	}

	public String getDelimiter() {
		return delimiter;
	}

	public void setDelimiter(String delimiter) {
		this.delimiter = delimiter;
	}

    public boolean getQuotedData() {
        return quoted;
    }

    public void setQuotedData(boolean quoted) {
        this.quoted = quoted;
    }

    public boolean getRecycle() {
        return recycle;
    }

    public void setRecycle(boolean recycle) {
        this.recycle = recycle;
    }

    public boolean getStopThread() {
        return stopThread;
    }

    public void setStopThread(boolean value) {
        this.stopThread = value;
    }

}
