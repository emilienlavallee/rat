# RAT 

## [Demo Video](https://drive.google.com/file/d/12AuYkuIeYhW4gcrlFuZbU0RZ_cfOTzE9/view?usp=sharing)

RAT (the Rat Aim Trainer) is an aim trainer for lacrosse wallball rats.

## Setup

#### Server
1. Install uv, Node 22, and pnpm
2. Run `tilt up -- --mode=dev`

### Mobile app
1. Copy tilt_config.example.json to tilt_config.json and fill in the fields with your JDK 21 installation and Android SDK installation.
2. Copy your models to `app/app/src/main/assets/models/<name>/{model.ncnn.bin, model.ncnn.param, model_fp32.tflite}`. See the **next section** on model training for this step.
2. Run `tilt up -- --mode=app`. App will be installed as RAT

### Model training

1. Gather your data set. For best results use around 1000 images from the same camera angle at different times of day.
2. Open the dataset in your favorite labelling software, like label-studio. Use this label configuration:
```
<View>
  <Image name="image" value="$image"/>
  <RectangleLabels name="label" toName="image">
    <Label value="ball"/>
  </RectangleLabels>
</View>
```
3. Label your data an export as "YOLO with images"
4. `cd scripts && uv run train.py --data <path/to/your/exported/dataset>`
5. Optionally export the model as TFLite using the `export_tflite.py` script.


