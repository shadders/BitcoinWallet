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

import java.util.Date;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;

/**
 * The SizedTable class is a JTable with column sizes based on the column data types
 */
public final class SizedTable extends JTable {

    /** Date column */
    public static final int DATE = 1;

    /** Name column */
    public static final int NAME = 2;

    /** Memo column */
    public static final int TYPE = 3;

    /** Amount column */
    public static final int AMOUNT = 4;

    /** Reconciled column */
    public static final int STATUS = 5;

    /** Bitcoin address */
    public static final int ADDRESS = 6;

    /**
     * Create a new sized table
     *
     * @param       tableModel      The table model
     * @param       columnTypes     Array of column types
     */
    public SizedTable(TableModel tableModel, int[] columnTypes) {

        //
        // Create the table
        //
        super(tableModel);

        //
        // Set the cell renderers and column widths
        //
        Component component;
        TableCellRenderer renderer;
        TableColumn column;
        TableColumnModel columnModel = getColumnModel();
        TableCellRenderer headRenderer = getTableHeader().getDefaultRenderer();
        if (headRenderer instanceof DefaultTableCellRenderer) {
            DefaultTableCellRenderer defaultRenderer = (DefaultTableCellRenderer)headRenderer;
            defaultRenderer.setHorizontalAlignment(JLabel.CENTER);
        }

        int columnCount = tableModel.getColumnCount();
        if (columnCount > columnTypes.length)
            throw new IllegalArgumentException("More columns than column types");

        for (int i=0; i<columnCount; i++) {
            Object value = null;
            column = columnModel.getColumn(i);
            switch (columnTypes[i]) {
                case DATE:
                    column.setCellRenderer(new DateRenderer());
                    value = new Date();
                    break;

                case NAME:
                    value = "mmmmmmmmmmmmmmmmmmmm";                 // 20 characters
                    break;

                case TYPE:
                    column.setCellRenderer(new StringRenderer(JLabel.CENTER));
                    value = "mmmmmmmmmm";                           // 10 characters
                    break;

                case AMOUNT:                                        // nnnn.nnnnnnnn
                    column.setCellRenderer(new AmountRenderer());
                    byte[] bigValue = new byte[] {0x7f, 0x7f, 0x7f, 0x7f, 0x7f};
                    value = new BigInteger(bigValue);
                    break;

                case STATUS:                                        // 9 character
                    column.setCellRenderer(new StringRenderer(JLabel.CENTER));
                    value = "Confirmed";
                    break;

                case ADDRESS:                                       // 34 characters
                    value = "0123456789AbCdEfGhIjKlMnOpQrStUvWx";
                    break;

                default:
                    throw new IllegalArgumentException("Unsupported column type "+columnTypes[i]);
            }

            component = headRenderer.getTableCellRendererComponent(this, tableModel.getColumnName(i),
                                                                   false, false, 0, i);
            int headWidth = component.getPreferredSize().width;
            renderer = column.getCellRenderer();
            if (renderer == null)
                renderer = getDefaultRenderer(tableModel.getColumnClass(i));
            component = renderer.getTableCellRendererComponent(this, value, false, false, 0, i);
            int cellWidth = component.getPreferredSize().width;
            column.setPreferredWidth(Math.max(headWidth+5, cellWidth+5));
        }

        //
        // Resize all column proportionally
        //
        setAutoResizeMode(AUTO_RESIZE_ALL_COLUMNS);
    }
}
