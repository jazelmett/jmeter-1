// $Header$
/*
 * Copyright 2001-2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package org.apache.jmeter.control;

import java.io.Serializable;

import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.StringProperty;
//NOTUSED import org.apache.jorphan.logging.LoggingManager;
//NOTUSED import org.apache.log.Logger;

/**
 * @version   $Revision$
 */
public class RunTime extends GenericController implements Serializable
{
    //NOTUSED private static Logger log = LoggingManager.getLoggerForClass();

    private final static String SECONDS = "RunTime.seconds";
    private volatile long startTime = 0;

	private int loopCount = 0; // for getIterCount
	
    public RunTime()
    {
    }

    public void setRuntime(long seconds)
    {
        setProperty(new LongProperty(SECONDS, seconds));
    }

    public void setRuntime(String seconds)
    {
        setProperty(new StringProperty(SECONDS, seconds));
    }

    public long getRuntime()
    {
        try
        {
            return Long.parseLong(getPropertyAsString(SECONDS));
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }

    public String getRuntimeString()
    {
        return getPropertyAsString(SECONDS);
    }

    /* (non-Javadoc)
     * @see org.apache.jmeter.control.Controller#isDone()
     */
    public boolean isDone()
    {
        if (getRuntime() > 0 && getSubControllers().size() > 0)
        {
            return super.isDone();
        }
        else
        {
            return true; // Runtime is zero - no point staying around
        }
    }

    private boolean endOfLoop()
    {
        return System.currentTimeMillis()-startTime >= 1000*getRuntime();
    }

	public Sampler next()
	{
		if (startTime == 0) startTime=System.currentTimeMillis();
		return super.next();
	}
    /* (non-Javadoc)
     * @see org.apache.jmeter.control.GenericController#nextIsNull()
     */
    protected Sampler nextIsNull() throws NextIsNullException
    {
        reInitialize();
        if (endOfLoop())
        {
            resetLoopCount();
            return null;
        }
        else
        {
            return next();
        }
    }

	protected void incrementLoopCount()
	{
		loopCount++;
	}
	protected void resetLoopCount()
	{
		loopCount=0;
		startTime=0;
	}
	/*
	 * This is needed for OnceOnly to work like other Loop Controllers
	 */
	protected int getIterCount()
	{
		return loopCount + 1;
	}
	protected void reInitialize()
	{
		setFirst(true);
		resetCurrent();
		incrementLoopCount();
	}
}