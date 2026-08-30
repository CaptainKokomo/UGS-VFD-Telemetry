package com.willwinder.universalgcodesender.fx;

import com.willwinder.universalgcodesender.fx.helper.SvgLoader;
import javafx.scene.Scene;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

public class Preloader extends javafx.application.Preloader {

    private Stage preloaderStage;

    @Override
    public void start(Stage primaryStage) {
        this.preloaderStage = primaryStage;

        ImageView logo = new ImageView(SvgLoader.loadIcon("icons/preloader.svg", 150).orElse(null));
        Label title = new Label("UGS VFD Telemetry");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white;");
        Label status = new Label("Starting CNC control...");
        status.setStyle("-fx-text-fill: #d0d0d0;");
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(32, 32);

        VBox content = new VBox(10, logo, title, status, progress);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(18));
        content.setStyle("-fx-background-color: #242424;");

        Scene scene = new Scene(content, 340, 280);
        scene.setFill(Color.web("#242424"));

        primaryStage.setTitle("UGS VFD Telemetry");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    @Override
    public void handleStateChangeNotification(StateChangeNotification info) {
        if (info.getType() == StateChangeNotification.Type.BEFORE_START) {
            preloaderStage.hide();
        }
    }
}
