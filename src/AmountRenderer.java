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

import java.math.BigInteger;

import javax.swing.table.*;
import javax.swing.*;
import java.awt.*;

/**
 * AmountRenderer is a cell renderer for use with a JTable column. It formats
 * BTC amounts with a minimum of 2 decimal digits.  Negative values will be
 * displayed in red while positive values will be displayed in black.
 */
public final class AmountRenderer extends DefaultTableCellRenderer {

    /**
     * Create an amount renderer.
     */
    public AmountRenderer() {
        super();
        setHorizontalAlignment(JLabel.RIGHT);
    }

    /**
     * Set the text value for the cell.  The supplied value must be a BigInteger.
     *
     * @param       value           The value for the cell
     */
    @Override
    public void setValue(Object value) {

        //
        // Return an empty string if the value is null
        //
        if (value == null) {
            setText(new String());
            return;
        }

        //
        // We must have a BigInteger
        //
        if (!(value instanceof BigInteger))
            throw new IllegalArgumentException("Value is not a BigInteger");

        //
        // Convert the amount to a formatted string
        //
        String text = Main.satoshiToString((BigInteger)value);

        //
        // Set the foreground color to red if the value is negative
        //
        if (text.charAt(0) == '-')
            setForeground(Color.RED);

        //
        // Set the text value for the number
        //
        setText(text);
    }

    /**
     * Get the table cell renderer component
     *
     * @param       table           The table
     * @param       value           The cell value
     * @param       isSelected      TRUE if the cell is selected
     * @param       hasFocus        TRUE if the cell has the focus
     * @param       row             Table row
     * @param       column          Table column
     */
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus,
                                                   int row, int column) {
        //
        // Set the foreground color in case it has been changed by setValue()
        //
        if (isSelected)
            setForeground(Color.WHITE);
        else
            setForeground(Color.BLACK);

        //
        // Return the table cell renderer component
        //
        return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
    }
}
