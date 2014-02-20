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
 * VerifyDialog verifies a message signature
 */
public class VerifyDialog extends JDialog implements ActionListener {

    /** Address field */
    private JTextField addressField;

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
    public VerifyDialog(JFrame parent) {
        super(parent, "Verify Message", Dialog.ModalityType.DOCUMENT_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        //
        // Create the address field
        //
        addressField = new JTextField("", 34);
        JPanel addressPane = new JPanel();
        addressPane.add(new JLabel("Address  ", JLabel.RIGHT));
        addressPane.add(addressField);
        //
        // Create the message text area
        //
        messageField = new JTextArea(6, 70);
        messageField.setLineWrap(true);
        messageField.setWrapStyleWord(true);
        messageField.setFont(addressField.getFont());
        scrollPane = new JScrollPane(messageField);
        JPanel messagePane = new JPanel();
        messagePane.add(new JLabel("Message  ", JLabel.RIGHT));
        messagePane.add(scrollPane);
        //
        // Create the signature field
        //
        signatureField = new JTextField("", 70);
        JPanel signaturePane = new JPanel();
        signaturePane.add(new JLabel("Signature  ", JLabel.RIGHT));
        signaturePane.add(signatureField);
        //
        // Create the buttons (Verify, Done)
        //
        JPanel buttonPane = new JPanel();
        buttonPane.setLayout(new BoxLayout(buttonPane, BoxLayout.X_AXIS));

        JButton button = new JButton("Verify");
        button.setActionCommand("verify");
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
        contentPane.add(addressPane);
        contentPane.add(Box.createVerticalStrut(15));
        contentPane.add(messagePane);
        contentPane.add(Box.createVerticalStrut(15));
        contentPane.add(signaturePane);
        contentPane.add(Box.createVerticalStrut(15));
        contentPane.add(buttonPane);
        setContentPane(contentPane);
    }

    /**
     * Show the verify dialog
     *
     * @param       parent              Parent frame
     */
    public static void showDialog(JFrame parent) {
        try {
            JDialog dialog = new VerifyDialog(parent);
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
        // "verify" - Verify the message
        // "done"   - All done
        //
        try {
            String action = ae.getActionCommand();
            switch (action) {
                case "verify":
                    String message = messageField.getText();
                    String address = addressField.getText();
                    String signature = signatureField.getText();
                    if (address.length() == 0) {
                        JOptionPane.showMessageDialog(this, "You must enter the signing address", "Error",
                                                      JOptionPane.ERROR_MESSAGE);
                    } else if (message.length() == 0) {
                        JOptionPane.showMessageDialog(this, "You must enter the message text to verify", "Error",
                                                      JOptionPane.ERROR_MESSAGE);
                    } else if (signature.length() == 0) {
                        JOptionPane.showMessageDialog(this, "You must enter the message signature", "Error",
                                                      JOptionPane.ERROR_MESSAGE);
                    } else {
                        if (ECKey.verifyMessage(address, message, signature))
                            JOptionPane.showMessageDialog(this, "The signature is valid", "Valid Signature",
                                                          JOptionPane.INFORMATION_MESSAGE);
                        else
                            JOptionPane.showMessageDialog(this, "The signature is not valid", "Invalid Signature",
                                                          JOptionPane.ERROR_MESSAGE);
                    }
                    break;
                case "done":
                    setVisible(false);
                    dispose();
                    break;
            }
        } catch (SignatureException exc) {
            JOptionPane.showMessageDialog(this, "The signature is not valid", "Invalid Signature",
                                          JOptionPane.ERROR_MESSAGE);
        } catch (Exception exc) {
            Main.logException("Exception while processing action event", exc);
        }
    }
}
