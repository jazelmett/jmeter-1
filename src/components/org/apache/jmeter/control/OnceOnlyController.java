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

import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.junit.stubs.TestSampler;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;

/**
 * @author     Michael Stover
 * Created      March 13, 2001
 * @version    $Revision$ Last updated: $Date$
 */
public class OnceOnlyController
    extends GenericController
    implements Serializable, LoopIterationListener
{

    /**
     * Constructor for the OnceOnlyController object.
     */
    public OnceOnlyController()
    {
    }

    /**
     * @see LoopIterationListener#iterationStart(LoopIterationEvent)
     */
    public void iterationStart(LoopIterationEvent event)
    {
        if (event.getIteration() == 1)
        {
            reInitialize();
        }
    }

    protected Sampler nextIsNull() throws NextIsNullException
    {
		setDone(true);
        return null;
    }

/////////////////////////// Start of Test Code ///////////////////////////

    public static class Test extends JMeterTestCase
    {
        public Test(String name)
        {
            super(name);
        }

        public void testProcessing() throws Exception
        {
            GenericController controller = new GenericController();
            GenericController sub_1 = new OnceOnlyController();
            sub_1.addTestElement(new TestSampler("one"));
            sub_1.addTestElement(new TestSampler("two"));
            controller.addTestElement(sub_1);
            controller.addTestElement(new TestSampler("three"));
            LoopController sub_2 = new LoopController();
            sub_2.setLoops(3);
            GenericController sub_3 = new GenericController();
            sub_2.addTestElement(new TestSampler("four"));
            sub_3.addTestElement(new TestSampler("five"));
            sub_3.addTestElement(new TestSampler("six"));
            sub_2.addTestElement(sub_3);
            sub_2.addTestElement(new TestSampler("seven"));
            controller.addTestElement(sub_2);
            String[] interleaveOrder = new String[] { "one", "two" };
            String[] order =
                new String[] {
                    "",
                    "",
                    "three",
                    "four",
                    "five",
                    "six",
                    "seven",
                    "four",
                    "five",
                    "six",
                    "seven",
                    "four",
                    "five",
                    "six",
                    "seven" };
            int counter = 15;
            controller.initialize();
            for (int i = 0; i < 4; i++)
            {
                assertEquals(15, counter);
                counter = 0;
                if (i > 0)
                {
                    counter = 2;
                }
                TestElement sampler = null;
                while ((sampler = controller.next()) != null)
                {
                    if (i == 0 && counter < 2)
                    {
                        assertEquals(
                            interleaveOrder[counter],
                            sampler.getPropertyAsString(TestElement.NAME));
                    }
                    else
                    {
                        assertEquals(
                            order[counter],
                            sampler.getPropertyAsString(TestElement.NAME));
                    }
                    counter++;
                }
            }
        }

        public void testProcessing2() throws Exception
        {
            GenericController controller = new GenericController();
            GenericController sub_1 = new OnceOnlyController();
            sub_1.addTestElement(new TestSampler("one"));
            sub_1.addTestElement(new TestSampler("two"));
            controller.addTestElement(sub_1);
            controller.addTestElement(new TestSampler("three"));
            LoopController sub_2 = new LoopController();
            sub_2.setLoops(3);
            OnceOnlyController sub_3 = new OnceOnlyController();
            sub_2.addTestElement(new TestSampler("four"));
            sub_3.addTestElement(new TestSampler("five"));
            sub_3.addTestElement(new TestSampler("six"));
            sub_2.addTestElement(sub_3);
            sub_2.addIterationListener(sub_3);
            sub_2.addTestElement(new TestSampler("seven"));
            controller.addTestElement(sub_2);
            String[] interleaveOrder = new String[] { "one", "two" };
            String[] order =
                new String[] {
                    "",
                    "",
                    "three",
                    "four",
                    "five",
                    "six",
                    "seven",
                    "four",
                    "seven",
                    "four",
                    "seven" };
            int counter = 11;
            controller.initialize();
            for (int i = 0; i < 4; i++)
            {
                assertEquals(11, counter);
                counter = 0;
                if (i > 0)
                {
                    counter = 2;
                }
                TestElement sampler = null;
                while ((sampler = controller.next()) != null)
                {
                    if (i == 0 && counter < 2)
                    {
                        assertEquals(
                            interleaveOrder[counter],
                            sampler.getPropertyAsString(TestElement.NAME));
                    }
                    else
                    {
                        assertEquals(
                            order[counter],
                            sampler.getPropertyAsString(TestElement.NAME));
                    }
                    counter++;
                }
            }
        }
    }
}
