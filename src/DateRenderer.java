/**
 * Copyright 2013 Ronald W Hoffman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package BitcoinWallet;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import javax.swing.*;
import javax.swing.table.*;

/**
 * DateRenderer is a cell renderer for use with a JTable column. It formats
 * dates as "mm/dd/yyyy".
 */
public final class DateRenderer extends DefaultTableCellRenderer {

    /** Gregorian calendar */
    private GregorianCalendar cal;

    /**
     * Create a date renderer
     */
    public DateRenderer() {
        super();
        setHorizontalAlignment(JLabel.CENTER);
        cal = new GregorianCalendar();
    }

    /**
     * Set the text value for the cell.  The supplied value must be a Date.
     *
     * @param       value           The value for the cell
     */
    @Override
    public void setValue(Object value) {
        if (value == null) {
            setText(new String());
            return;
        }
        if (!(value instanceof Date))
            throw new IllegalArgumentException("Value is not a Date");
        cal.setTime((Date)value);
        setText(String.format("%02d/%02d/%04d",
                              cal.get(Calendar.MONTH)+1,
                              cal.get(Calendar.DAY_OF_MONTH),
                              cal.get(Calendar.YEAR)));
    }
}
