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

import java.util.List;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.Toolkit;

/**
 * SignDialog will sign a message using a key contained in the wallet.  The signature can then be copied to the
 * system clipboard.
 */
public class SignDialog extends JDialog implements ActionListener {

    /** Name field */
    private JComboBox nameField;

    /** Message field */
    private JTextArea messageField;

    /** Message scroll pane */
    private JScrollPane scrollPane;

    /** Signature field */
    private JTextField signatureField;

    /**
     * Create the dialog
     *
     * @param       parent          Parent frame
     */
    public SignDialog(JFrame parent) {
        super(parent, "Sign Message", Dialog.ModalityType.DOCUMENT_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        //
        // Create the name field
        //
        String[] keyLabels = new String[Parameters.keys.size()];
        int index = 0;
        for (ECKey key : Parameters.keys)
            keyLabels[index++] = key.getLabel();
        nameField = new JComboBox(keyLabels);
        nameField.setPreferredSize(new Dimension(200, 25));
        JPanel namePane = new JPanel();
        namePane.add(new JLabel("Key  ", JLabel.RIGHT));
        namePane.add(nameField);
        //
        // Create the message text area
        //
        messageField = new JTextArea(10, 70);
        messageField.setLineWrap(true);
        messageField.setWrapStyleWord(true);
        messageField.setFont(nameField.getFont());
        scrollPane = new JScrollPane(messageField);
        JPanel messagePane = new JPanel();
        messagePane.add(new JLabel("Message  ", JLabel.RIGHT));
        messagePane.add(scrollPane);
        //
        // Create the signature field
        //
        signatureField = new JTextField("", 70);
        signatureField.setEditable(false);
        JPanel signaturePane = new JPanel();
        signaturePane.add(new JLabel("Signature  ", JLabel.RIGHT));
        signaturePane.add(signatureField);
        //
        // Create the buttons (Sign, Copy, Done)
        //
        JPanel buttonPane = new JPanel();
        buttonPane.setLayout(new BoxLayout(buttonPane, BoxLayout.X_AXIS));

        JButton button = new JButton("Sign");
        button.setActionCommand("sign");
        button.addActionListener(this);
        buttonPane.add(button);

        buttonPane.add(Box.createHorizontalStrut(10));

        button = new JButton("Copy");
        button.setActionCommand("copy");
        button.addActionListener(this);
        buttonPane.add(button);

        buttonPane.add(Box.createHorizontalStrut(10));

        button = new JButton("Done");
        button.setActionCommand("done");
        button.addActionListener(this);
        buttonPane.add(button);
        //
        // Set up the content pane
        //
        JPanel contentPane = new JPanel();
        contentPane.setLayout(new BoxLayout(contentPane, BoxLayout.Y_AXIS));
        contentPane.setOpaque(true);
        contentPane.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        contentPane.add(namePane);
        contentPane.add(Box.createVerticalStrut(15));
        contentPane.add(messagePane);
        contentPane.add(Box.createVerticalStrut(15));
        contentPane.add(signaturePane);
        contentPane.add(Box.createVerticalStrut(15));
        contentPane.add(buttonPane);
        setContentPane(contentPane);
    }

    /**
     * Show the sign dialog
     *
     * @param       parent              Parent frame
     */
    public static void showDialog(JFrame parent) {
        try {
            JDialog dialog = new SignDialog(parent);
            dialog.pack();
            dialog.setLocationRelativeTo(parent);
            dialog.setVisible(true);
        } catch (Exception exc) {
            Main.logException("Exception while displaying dialog", exc);
        }
    }

    /**
     * Action performed (ActionListener interface)
     *
     * @param   ae              Action event
     */
    @Override
    public void actionPerformed(ActionEvent ae) {
        //
        // "sign" - Sign the message
        // "copy" - Copy signature to clipboard
        // "done" - All done
        //
        try {
            String action = ae.getActionCommand();
            switch (action) {
                case "sign":
                    String message = messageField.getText();
                    if (message.length() == 0) {
                        JOptionPane.showMessageDialog(this, "You must enter the message text to sign", "Error",
                                                      JOptionPane.ERROR_MESSAGE);
                    } else {
                        int index = nameField.getSelectedIndex();
                        ECKey key = Parameters.keys.get(index);
                        String signature = key.signMessage(message);
                        signatureField.setText(signature);
                    }
                    break;
                case "copy":
                    String signature = signatureField.getText();
                    StringSelection sel = new StringSelection(signature);
                    Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                    cb.setContents(sel, null);
                    break;
                case "done":
                    setVisible(false);
                    dispose();
                    break;
            }
        } catch (ECException exc) {
            Main.logException("Unable to sign message", exc);
        } catch (Exception exc) {
            Main.logException("Exception while processing action event", exc);
        }
    }
}
