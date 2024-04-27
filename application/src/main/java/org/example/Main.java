package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Arrays;

public class Main {
    private static final int BUTTON_1_ID = 1;
    private static final int BUTTON_2_ID = 2;

    static void makeFrame() {
        JFrame frame = new JFrame("Button Test");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());

        JButton button1 = new JButton("Option 1");
        button1.addActionListener(new ButtonClickListener(BUTTON_1_ID));
        panel.add(button1);

        JButton button2 = new JButton("Option 2");
        button2.addActionListener(new ButtonClickListener(BUTTON_2_ID));
        panel.add(button2);

        frame.getContentPane().add(panel);
        frame.pack();
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        makeFrame();
    }

    private static class ButtonClickListener implements ActionListener {

        private final int buttonID;

        public ButtonClickListener(int buttonID) {
            this.buttonID = buttonID;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            // Simulate processing based on button ID (replace with your game logic)
            System.out.println("Button " + buttonID + " pressed!");
        }
    }
}


/*import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;

public class com.example.MainClass {
	static JFrame frame;
	static JPanel panel;
	static JButton button;
	public static void makeFrame() {
		frame = new JFrame("com.example.MainClass");
		panel = new JPanel();
		button = new JButton("Test");
		panel.add(button);
		frame.add(panel);
		frame.setSize(200,300);
		frame.setVisible(true);
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
	}
	public static void main(String[] args) {

	System.out.println("Hello this is the main");
	makeFrame();

	}

}*/