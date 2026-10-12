-- Reference data required by the application in every environment.

INSERT INTO roles (id, name) VALUES
    (1, 'SUPER_ADMIN'),
    (2, 'GYM_ADMIN'),
    (3, 'MANAGER'),
    (4, 'TRAINER'),
    (5, 'RECEPTIONIST'),
    (6, 'MEMBER');

INSERT INTO exercises (name, muscle_group, equipment, description) VALUES
    ('Barbell Back Squat',      'LEGS',      'Barbell',        'Compound lower-body lift; keep chest up and knees tracking toes.'),
    ('Romanian Deadlift',       'HAMSTRINGS','Barbell',        'Hip hinge with soft knees, bar close to the legs.'),
    ('Conventional Deadlift',   'BACK',      'Barbell',        'Full-body pull from the floor with neutral spine.'),
    ('Barbell Bench Press',     'CHEST',     'Barbell',        'Horizontal press; shoulder blades retracted.'),
    ('Incline Dumbbell Press',  'CHEST',     'Dumbbells',      'Upper-chest emphasis at 30-45 degree incline.'),
    ('Overhead Press',          'SHOULDERS', 'Barbell',        'Standing strict press.'),
    ('Lateral Raise',           'SHOULDERS', 'Dumbbells',      'Raise to shoulder height with slight elbow bend.'),
    ('Pull-Up',                 'BACK',      'Pull-up bar',    'Full range vertical pull.'),
    ('Lat Pulldown',            'BACK',      'Cable machine',  'Vertical pull to upper chest.'),
    ('Seated Cable Row',        'BACK',      'Cable machine',  'Horizontal pull, squeeze shoulder blades.'),
    ('Barbell Curl',            'ARMS',      'Barbell',        'Elbows pinned to sides.'),
    ('Triceps Rope Pushdown',   'ARMS',      'Cable machine',  'Full elbow extension, split the rope at the bottom.'),
    ('Walking Lunge',           'LEGS',      'Dumbbells',      'Alternating forward lunges.'),
    ('Leg Press',               'LEGS',      'Leg press',      'Machine compound leg exercise.'),
    ('Plank',                   'CORE',      NULL,             'Isometric core hold.'),
    ('Hanging Leg Raise',       'CORE',      'Pull-up bar',    'Controlled hip flexion while hanging.'),
    ('Treadmill Run',           'CARDIO',    'Treadmill',      'Steady-state or interval running.'),
    ('Rowing Machine',          'CARDIO',    'Rower',          'Full-body conditioning.'),
    ('Kettlebell Swing',        'FULL_BODY', 'Kettlebell',     'Explosive hip hinge.'),
    ('Burpee',                  'FULL_BODY', NULL,             'Bodyweight conditioning movement.');
