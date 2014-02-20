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

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * Dialog to create a new address list entry or edit an existing entry
 */
public class AddressEditDialog extends JDialog implements ActionListener {

    /** Updated address */
    private Address updatedAddress;

    /** Name field */
    private JTextField nameField;

    /** Address field */
    private JTextField addressField;

    /**
     * Create the address edit dialog
     *
     * @param       parent          Parent dialog
     * @param       address         Address to edit or null for a new address
     * @param       editAddress     TRUE if the address can be modified
     */
    public AddressEditDialog(JDialog parent, Address address, boolean editAddress) {
        super(parent, "Edit Address", Dialog.ModalityType.DOCUMENT_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        //
        // Create the edit pane
        //
        //    Name:         <text-field>
        //    Address:      <text-field>
        //
        JPanel editPane = new JPanel();
        editPane.setLayout(new BoxLayout(editPane, BoxLayout.X_AXIS));

        nameField = new JTextField(address!=null?address.getLabel():"", 32);
        addressField = new JTextField(address!=null?address.toString():"", 34);
        if (!editAddress)
            addressField.setEditable(false);

        JPanel namePane = new JPanel();
        namePane.add(new JLabel("Name:", JLabel.RIGHT));
        namePane.add(nameField);
        editPane.add(namePane);

        editPane.add(Box.createHorizontalStrut(10));

        JPanel addressPane = new JPanel();
        addressPane.add(new JLabel("Address:", JLabel.RIGHT));
        addressPane.add(addressField);
        editPane.add(addressPane);
        //
        // Create the buttons (Save, Cancel)
        //
        JPanel buttonPane = new JPanel();
        buttonPane.setLayout(new BoxLayout(buttonPane, BoxLayout.X_AXIS));

        JButton button = new JButton("Save");
        button.setActionCommand("save");
        button.addActionListener(this);
        buttonPane.add(button);

        buttonPane.add(Box.createHorizontalStrut(10));

        button = new JButton("Cancel");
        button.setActionCommand("cancel");
        button.addActionListener(this);
        buttonPane.add(button);
        //
        // Set up the content pane
        //
        JPanel contentPane = new JPanel();
        contentPane.setLayout(new BoxLayout(contentPane, BoxLayout.Y_AXIS));
        contentPane.setOpaque(true);
        contentPane.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        contentPane.add(editPane);
        contentPane.add(Box.createVerticalStrut(15));
        contentPane.add(buttonPane);
        setContentPane(contentPane);
    }

    /**
     * Show the address dialog
     *
     * @param       parent              Parent dialog
     * @param       address             Address
     * @param       editAddress         TRUE if the address can be edited
     * @return                          Updated address or null if edit canceled
     */
    public static Address showDialog(JDialog parent, Address address, boolean editAddress) {
        Address updatedAddress = null;
        try {
            AddressEditDialog dialog = new AddressEditDialog(parent, address, editAddress);
            dialog.pack();
            dialog.setLocationRelativeTo(parent);
            dialog.setVisible(true);
            updatedAddress = dialog.updatedAddress;
        } catch (Exception exc) {
            Main.logException("Exception while displaying dialog", exc);
        }
        return updatedAddress;
    }

    /**
     * Action performed (ActionListener interface)
     *
     * @param   ae              Action event
     */
    public void actionPerformed(ActionEvent ae) {
        //
        // "save"   - Save the table entry
        // "cancel" - Cancel the edit
        //
        try {
            String action = ae.getActionCommand();
            switch (action) {
                case "save":
                    if (processFields()) {
                        setVisible(false);
                        dispose();
                    }
                    break;
                case "cancel":
                    updatedAddress = null;
                    setVisible(false);
                    dispose();
                    break;
            }
        } catch (Exception exc) {
            Main.logException("Exception while processing action event", exc);
        }
    }

    /**
     * Process the name and address fields
     *
     * @return      TRUE if the fields are valid
     */
    private boolean processFields() {
        String name = nameField.getText();
        String addr = addressField.getText();
        //
        // Make sure we have name and address values
        //
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "You must specify a name", "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (name.length() > 64) {
            JOptionPane.showMessageDialog(this, "The name must be 64 characters or less", "Error",
                                          JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (addr.isEmpty()) {
            JOptionPane.showMessageDialog(this, "You must specify an address", "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        //
        // Create the updated address
        //
        boolean valid = true;
        try {
            updatedAddress = new Address(addr, name);
        } catch (AddressFormatException exc) {
            JOptionPane.showMessageDialog(this, "Address is not valid", "Error", JOptionPane.ERROR_MESSAGE);
            valid = false;
        }
        return valid;
    }
}
